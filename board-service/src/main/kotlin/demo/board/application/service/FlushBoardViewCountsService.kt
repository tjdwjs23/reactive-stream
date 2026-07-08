package demo.board.application.service

import demo.board.application.port.`in`.FlushBoardViewCountsUseCase
import demo.board.application.port.`in`.FlushViewCountsResult
import demo.board.application.port.out.BoardEventOutboxPort
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardViewCountPort
import demo.board.application.port.out.DistributedLockPort
import demo.board.application.port.out.ObservabilityPort
import demo.board.application.port.out.TransactionRunnerPort
import demo.board.domain.model.Board
import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Redis 버퍼의 조회수 델타를 DB로 write-back합니다. MVC/블로킹 스택이라 평범한 블로킹 함수입니다.
// - Redis 버퍼를 스냅샷으로 옮겨두고(snapshot), 청크 단위 단일 UPDATE(배치)로 view_count에 더합니다.
// - 한 청크 실패가 전체를 막지 않도록 청크별로 내결함성 처리합니다(아카이브 배치와 동일한 철학).
//
// 왜 배치인가: 건별 UPDATE(addViewCount N회)는 DB 라운드트립이 N번 발생해, 플러시 대상이 수만 건이면
// 순차 왕복만으로 십수 초가 걸립니다. 청크당 단일 UPDATE(addViewCountsBatch)로 왕복을 N/chunkSize로 줄입니다.
//
// 내구성(commit-then-delete): 스냅샷을 곧바로 비우지 않고, 청크의 DB 반영이 성공한 뒤에만 그 청크를
// 버퍼에서 지웁니다(removeDrained). 반영 전에 죽으면 스냅샷이 남아 다음 플러시가 재시도하므로
// 델타가 유실되지 않습니다(유실 대신 크래시 시 약간의 중복 계수를 허용하는 at-least-once — 조회수는 근사값).
//
// 상호배제(분산 락): 스냅샷~반영~정리(DRAINING)가 서로 뒤엉키면 removeDrained가 짝을 잃어 유실/이중반영이
// 생기므로, 플러시 전체를 "한 번에 하나"로 직렬화합니다. DistributedLockPort(Redis SET NX)로 클러스터 전역에서
// 직렬화하며, 이 락은 인스턴스 내부의 스케줄/admin 동시 호출까지 함께 막습니다.
@Service
class FlushBoardViewCountsService(
    private val boardViewCountPort: BoardViewCountPort,
    private val boardRepositoryPort: BoardRepositoryPort,
    // DB 반영과 "같은 트랜잭션"으로 UPDATED 이벤트를 남겨, 검색 색인(ES)의 조회수도 최종 동기화합니다(Transactional Outbox).
    private val boardEventOutboxPort: BoardEventOutboxPort,
    // "조회수 반영(UPDATE) + 아웃박스 기록"을 청크 단위로 원자적으로 묶는 트랜잭션 경계.
    private val transactionRunner: TransactionRunnerPort,
    private val observability: ObservabilityPort,
    // 클러스터 전역 상호배제. 다른 인스턴스가 이미 플러시 중이면 이번 호출은 락을 못 잡고 스킵합니다.
    private val distributedLock: DistributedLockPort,
    // 이벤트 발생 시각 주입용 시계(도메인/서비스가 벽시계를 직접 읽지 않음 — BoardService와 동일 원칙, 테스트에서 고정 가능).
    private val clock: Clock,
    // 한 UPDATE에 묶을 게시글 수. 클수록 왕복↓(하지만 실패 시 재시도 단위↑·문장당 바인딩↑).
    @Value("\${board.view-count.flush-chunk-size:1000}") private val chunkSize: Int,
    // 플러시 락 TTL(ms). 홀더가 락을 쥔 채 죽어도 이 시간 뒤 자동 해제됩니다. 최대 플러시 소요보다 넉넉히 잡습니다.
    @Value("\${board.view-count.flush-lock-ttl-ms:300000}") private val lockTtlMs: Long,
) : FlushBoardViewCountsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun flush(): FlushViewCountsResult {
        val result =
            distributedLock.withLock(FLUSH_LOCK_KEY, Duration.ofMillis(lockTtlMs)) {
                doFlush()
            }
        // 락을 못 잡았다 = 다른 인스턴스/스레드가 이미 플러시 중. 이번 차례는 안전하게 건너뜁니다
        // (버퍼는 그대로 남아 지금 플러시 중인 쪽이 반영하거나, 다음 주기가 이어받습니다 — 유실 없음).
        return result ?: FlushViewCountsResult(boards = 0, updatedRows = 0, failed = 0).also {
            log.info("view count flush skipped: another instance/thread holds the flush lock")
        }
    }

    private fun doFlush(): FlushViewCountsResult {
        val deltas = boardViewCountPort.snapshotPendingDeltas()
        if (deltas.isEmpty()) return FlushViewCountsResult(boards = 0, updatedRows = 0, failed = 0)

        var updatedRows = 0
        var failed = 0
        for (chunk in deltas.entries.chunked(chunkSize)) {
            val chunkMap = chunk.associate { it.key to it.value }
            try {
                // DB 조회수 반영 + UPDATED 아웃박스 이벤트 기록을 하나의 트랜잭션으로 묶습니다(둘 다 커밋되거나 둘 다 롤백).
                // RETURNING으로 받은 최신 상태(반영 후 view_count 포함)로 이벤트를 만들어, search-indexer가 ES 문서의
                // 조회수를 최종 동기화합니다. 이벤트는 절대값(누적 view_count)을 실어 재전달돼도 멱등합니다.
                val updated =
                    transactionRunner.execute {
                        val boards = boardRepositoryPort.addViewCountsBatch(chunkMap)
                        if (boards.isNotEmpty()) {
                            boardEventOutboxPort.recordAll(boards.map { it.toViewCountUpdatedEvent() })
                        }
                        boards
                    }
                updatedRows += updated.size
                // commit-then-delete: DB 반영이 성공한 뒤에만 버퍼에서 지웁니다.
                // 반영과 제거 사이에 죽으면 남아 다음 플러시가 재시도합니다(실패 청크도 지우지 않아 재시도됨).
                boardViewCountPort.removeDrained(chunkMap.keys)
            } catch (e: Exception) {
                failed += chunkMap.size
                log.error(
                    "Failed to flush view count chunk (size={}). Skip; will retry next flush.",
                    chunkMap.size,
                    e,
                )
            }
        }

        return FlushViewCountsResult(boards = deltas.size, updatedRows = updatedRows, failed = failed)
            .also {
                log.info("view count flush finished: {}", it)
                // DB에 실제 반영된(실패 제외) 게시글 수를 비즈니스 메트릭으로 기록합니다.
                observability.viewCountsFlushed(it.boards - it.failed)
            }
    }

    // 조회수 반영 결과(RETURNING)로 UPDATED 이벤트를 만듭니다. 색인 문서를 그대로 복원할 수 있도록
    // 최신 필드(반영 후 viewCount 포함)를 모두 싣습니다 — BoardService의 쓰기 경로 UPDATED 이벤트와 동일한 형태라
    // 소비자(search-indexer)는 별도 분기 없이 기존 upsert 경로로 처리합니다(절대값이라 재전달에도 멱등).
    private fun Board.toViewCountUpdatedEvent(): BoardChangedEvent =
        BoardChangedEvent(
            eventId = UUID.randomUUID().toString(),
            boardId = requireNotNull(id) { "반영된 게시글은 id가 있어야 합니다" },
            type = BoardChangeType.UPDATED,
            title = title,
            content = content,
            authorId = authorId,
            viewCount = viewCount,
            createdAt = createdAt,
            occurredAt = Instant.now(clock),
        )

    private companion object {
        // 클러스터 전역에서 공유하는 플러시 락 키(모든 인스턴스가 같은 키를 두고 경합).
        const val FLUSH_LOCK_KEY = "board:views:flush-lock"
    }
}
