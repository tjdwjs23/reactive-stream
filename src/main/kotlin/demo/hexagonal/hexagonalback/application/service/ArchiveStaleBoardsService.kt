package demo.hexagonal.hexagonalback.application.service

import demo.hexagonal.hexagonalback.application.port.`in`.ArchiveResult
import demo.hexagonal.hexagonalback.application.port.`in`.ArchiveStaleBoardsCommand
import demo.hexagonal.hexagonalback.application.port.`in`.ArchiveStaleBoardsUseCase
import demo.hexagonal.hexagonalback.application.port.out.BoardBatchQueryPort
import demo.hexagonal.hexagonalback.domain.model.Board
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

// Service는 "흐름 제어(오케스트레이션)"만 담당합니다.
// - 무엇이 아카이브 대상인지 판단하는 규칙 → Board.isStale() (도메인)
// - 어떻게 저장소에서 읽고 지우는지 → BoardBatchQueryPort (어댑터)
// 여기서는 스트리밍/청크/동시성/백프레셔/내결함성 흐름만 조립합니다.
//
// 배치 서비스에는 클래스 레벨 @Transactional을 붙이지 않습니다.
// 수백만 건을 하나의 트랜잭션으로 묶으면 커넥션·락을 오래 잡기 때문입니다.
// 삭제는 청크 단위(BoardBatchQueryPort.deleteByIds)로 각각 짧게 커밋합니다.
@Service
class ArchiveStaleBoardsService(
    private val boardBatchQueryPort: BoardBatchQueryPort,
) : ArchiveStaleBoardsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun archiveStaleBoards(command: ArchiveStaleBoardsCommand): ArchiveResult {
        val now = LocalDateTime.now()
        val threshold = now.minusDays(command.retentionDays)

        val scanned = AtomicInteger()
        val deleted = AtomicInteger()
        val failedChunks = AtomicInteger()

        // 호출자의 코루틴 컨텍스트(디스패처)를 그대로 상속합니다. R2DBC는 논블로킹이라
        // Dispatchers.IO로 스레드를 따로 잡을 필요가 없습니다.
        coroutineScope {
            // 바운드 채널이 곧 백프레셔 장치입니다(murray의 바운드 큐 + WaitPolicy와 같은 의도).
            // 소비자(워커)가 밀리면 send가 suspend되어 생산자가 DB 페이지를 더 읽지 않습니다.
            // 덕분에 "생산자가 소비자보다 빨라 메모리가 폭증"하는 상황이 원천 차단됩니다.
            val channel = Channel<List<Board>>(capacity = command.concurrency)

            val producer =
                launch {
                    // Flow에는 chunked가 없으므로 흐르는 원소를 chunkSize만큼 모아 청크로 전송합니다.
                    val buffer = ArrayList<Board>(command.chunkSize)
                    boardBatchQueryPort
                        .findStaleBoards(threshold, command.chunkSize)
                        .collect { board ->
                            buffer.add(board)
                            if (buffer.size >= command.chunkSize) {
                                channel.send(ArrayList(buffer))
                                buffer.clear()
                            }
                        }
                    if (buffer.isNotEmpty()) channel.send(ArrayList(buffer))
                    channel.close()
                }

            val workers =
                List(command.concurrency) {
                    launch {
                        for (chunk in channel) {
                            processChunk(chunk, now, command.retentionDays, scanned, deleted, failedChunks)
                        }
                    }
                }

            producer.join()
            workers.joinAll()
        }

        return ArchiveResult(
            scanned = scanned.get(),
            deleted = deleted.get(),
            failedChunks = failedChunks.get(),
        ).also { log.info("archiveStaleBoards finished: {}", it) }
    }

    private suspend fun processChunk(
        chunk: List<Board>,
        now: LocalDateTime,
        retentionDays: Long,
        scanned: AtomicInteger,
        deleted: AtomicInteger,
        failedChunks: AtomicInteger,
    ) {
        scanned.addAndGet(chunk.size)

        // 도메인 규칙(Board.isStale)이 최종 권위입니다.
        // DB 조회의 createdAt 필터는 성능을 위한 1차 필터일 뿐, 삭제 여부는 도메인이 다시 확정합니다.
        val targetIds =
            chunk
                .filter { it.isStale(now, retentionDays) }
                .mapNotNull { it.id }
        if (targetIds.isEmpty()) return

        // 내결함성: 한 청크가 실패해도 배치 전체를 멈추지 않고 건너뜁니다(murray의 skipPolicy).
        runCatching { boardBatchQueryPort.deleteByIds(targetIds) }
            .onSuccess { deleted.addAndGet(it) }
            .onFailure { e ->
                failedChunks.incrementAndGet()
                log.error("Failed to delete chunk (size={}). Skip and continue.", targetIds.size, e)
            }
    }
}
