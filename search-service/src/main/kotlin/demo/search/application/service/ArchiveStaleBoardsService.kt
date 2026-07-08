package demo.search.application.service

import demo.search.application.port.`in`.ArchiveResult
import demo.search.application.port.`in`.ArchiveStaleBoardsCommand
import demo.search.application.port.`in`.ArchiveStaleBoardsUseCase
import demo.search.application.port.out.BoardBatchQueryPort
import demo.search.application.port.out.BoardEventOutboxPort
import demo.search.application.port.out.ObservabilityPort
import demo.search.application.port.out.TransactionRunnerPort
import demo.search.domain.model.Board
import demo.search.events.BoardChangeType
import demo.search.events.BoardChangedEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// Service는 "흐름 제어(오케스트레이션)"만 담당합니다.
// - 무엇이 아카이브 대상인지 판단하는 규칙 → Board.isStale() (도메인)
// - 어떻게 저장소에서 읽고 지우는지 → BoardBatchQueryPort (어댑터)
// 여기서는 스트리밍/청크/동시성/백프레셔/내결함성 흐름만 조립합니다.
//
// 이 서비스만 코루틴을 씁니다(웹/다른 서비스는 블로킹). 대량 삭제를 생산자 1 + 워커 N으로 팬아웃하는
// "구조적 동시성"이 코루틴이 진짜 값을 내는 지점이기 때문입니다 — 바운드 Channel이 백프레셔를,
// coroutineScope가 전 워커의 합류(취소 전파 포함)를 보장합니다. 포트는 블로킹(JPA/JDBC)이라 워커/생산자는
// Dispatchers.IO에서 돌려, 블로킹 호출이 코루틴 기본 디스패처를 굶기지 않게 합니다.
//
// 배치 서비스에는 클래스 레벨 @Transactional을 붙이지 않습니다(수백만 건을 한 트랜잭션으로 묶으면 커넥션·락을
// 오래 잡음). 대신 청크 단위로 "삭제 + 아웃박스 DELETED 이벤트 기록"을 하나의 짧은 트랜잭션으로 묶어 커밋합니다
// (BoardService의 단건 삭제와 동일한 Transactional Outbox 원칙 — 삭제도 반드시 DELETED 이벤트를 남깁니다).
@Service
class ArchiveStaleBoardsService(
    private val boardBatchQueryPort: BoardBatchQueryPort,
    // 삭제 이벤트를 아웃박스에 남겨 search-indexer가 ES 문서를 지우게 합니다(색인 정합성).
    private val boardEventOutboxPort: BoardEventOutboxPort,
    // "삭제 + 아웃박스 기록"을 청크 단위로 원자적으로 묶는 트랜잭션 경계.
    private val transactionRunner: TransactionRunnerPort,
    private val observability: ObservabilityPort,
    // 배치 실행 시각을 주입된 시계에서 얻습니다(벽시계 직접 호출 대신 — 테스트에서 고정 가능, BoardService와 동일 원칙).
    private val clock: Clock,
) : ArchiveStaleBoardsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun archiveStaleBoards(command: ArchiveStaleBoardsCommand): ArchiveResult {
        val now = LocalDateTime.now(clock)
        val threshold = now.minusDays(command.retentionDays)

        val scanned = AtomicInteger()
        val deleted = AtomicInteger()
        val failedChunks = AtomicInteger()
        // 삭제를 실제로 시도한(대상이 있는) 청크 수. "전부 실패"를 판정하는 분모입니다.
        val attemptedChunks = AtomicInteger()

        coroutineScope {
            // 바운드 채널이 곧 백프레셔 장치입니다 — 소비자(워커)가 밀리면 send가 suspend되어 생산자가
            // DB 페이지를 더 읽지 않습니다. "생산자가 소비자보다 빨라 메모리가 폭증"하는 상황을 원천 차단합니다.
            val channel = Channel<List<Board>>(capacity = command.concurrency)

            val producer =
                launch(Dispatchers.IO) {
                    // 키셋 페이지네이션: "마지막으로 읽은 id 이후"를 조건으로 다음 페이지를 읽습니다(블로킹 JPA/JDSL).
                    // 워커가 동시에 삭제해도 커서는 id 오름차순으로만 전진하므로 삭제된 행이 다시 잡히지 않습니다.
                    var lastId = 0L
                    while (true) {
                        val page = boardBatchQueryPort.findStalePage(threshold, lastId, command.chunkSize)
                        if (page.isEmpty()) break
                        channel.send(page) // 한 페이지 = 한 청크
                        lastId = page.last().id ?: break
                        if (page.size < command.chunkSize) break
                    }
                    channel.close()
                }

            val workers =
                List(command.concurrency) {
                    launch(Dispatchers.IO) {
                        for (chunk in channel) {
                            processChunk(
                                chunk,
                                now,
                                command.retentionDays,
                                scanned,
                                deleted,
                                failedChunks,
                                attemptedChunks,
                            )
                        }
                    }
                }

            producer.join()
            workers.joinAll()
        }

        val result =
            ArchiveResult(
                scanned = scanned.get(),
                deleted = deleted.get(),
                failedChunks = failedChunks.get(),
            ).also {
                log.info("archiveStaleBoards finished: {}", it)
                observability.boardsArchived(it.deleted)
            }

        // 부분 실패는 result.failedChunks로 보고하지만, "시도한 모든 청크가 실패"한 경우는
        // 예외만 확인하는 스케줄러가 성공으로 오인하지 않도록 예외로 신호합니다(전체 실패 = 잡 실패).
        val attempted = attemptedChunks.get()
        if (attempted > 0 && failedChunks.get() == attempted) {
            throw IllegalStateException("archiveStaleBoards: 시도한 $attempted 개 청크 삭제가 모두 실패했습니다.")
        }

        return result
    }

    // 워커에서 블로킹으로 실행됩니다(suspend 아님). 실제 삭제/이벤트 기록은 블로킹 트랜잭션 경계 안에서 수행합니다.
    private fun processChunk(
        chunk: List<Board>,
        now: LocalDateTime,
        retentionDays: Long,
        scanned: AtomicInteger,
        deleted: AtomicInteger,
        failedChunks: AtomicInteger,
        attemptedChunks: AtomicInteger,
    ) {
        scanned.addAndGet(chunk.size)

        // 도메인 규칙(Board.isStale)이 최종 권위입니다.
        // DB 조회의 createdAt 필터는 성능을 위한 1차 필터일 뿐, 삭제 여부는 도메인이 다시 확정합니다.
        val targetIds =
            chunk
                .filter { it.isStale(now, retentionDays) }
                .mapNotNull { it.id }
        if (targetIds.isEmpty()) return

        attemptedChunks.incrementAndGet()

        // 내결함성: 한 청크가 실패해도 배치 전체를 멈추지 않고 건너뜁니다.
        // 삭제(DELETE)와 DELETED 이벤트 기록을 한 트랜잭션으로 묶습니다 — 둘 다 커밋되거나 둘 다 롤백됩니다.
        // 실패한 청크는 커밋되지 않으므로 다음 실행에서 다시 대상이 됩니다.
        // 상위 취소(CancellationException)는 삼키지 않고 재던져 구조적 동시성을 지킵니다.
        try {
            val removed =
                transactionRunner.execute {
                    val count = boardBatchQueryPort.deleteByIds(targetIds)
                    boardEventOutboxPort.recordAll(targetIds.map { deletedEvent(it) })
                    count
                }
            deleted.addAndGet(removed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedChunks.incrementAndGet()
            log.error("Failed to delete chunk (size={}). Skip and continue.", targetIds.size, e)
        }
    }

    // 삭제 이벤트: 색인에서 제거만 하면 되므로 본문 필드는 비웁니다(boardId만 유효). BoardService의 단건 삭제와 동일한 형태.
    private fun deletedEvent(boardId: Long): BoardChangedEvent =
        BoardChangedEvent(
            eventId = UUID.randomUUID().toString(),
            boardId = boardId,
            type = BoardChangeType.DELETED,
            occurredAt = Instant.now(clock),
        )
}
