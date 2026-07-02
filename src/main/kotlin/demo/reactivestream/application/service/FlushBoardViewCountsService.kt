package demo.reactivestream.application.service

import demo.reactivestream.application.port.`in`.FlushBoardViewCountsUseCase
import demo.reactivestream.application.port.`in`.FlushViewCountsResult
import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.application.port.out.BoardViewCountPort
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// Redis 버퍼의 조회수 델타를 DB로 write-back합니다.
// - Redis에서 원자적으로 델타를 꺼내고(drain), 게시글별로 view_count에 더합니다.
// - 한 건 실패가 전체를 막지 않도록 건별로 내결함성 처리합니다(아카이브 배치와 동일한 철학).
//
// 주의(학습용 단순화): drain 이후 DB 반영 전에 프로세스가 죽으면 그 델타는 유실됩니다.
// 실무에선 반영 성공분만 커밋 후 삭제하거나, 실패분을 다시 버퍼로 되돌리는 보상 로직을 둡니다.
@Service
class FlushBoardViewCountsService(
    private val boardViewCountPort: BoardViewCountPort,
    private val boardRepositoryPort: BoardRepositoryPort,
) : FlushBoardViewCountsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun flush(): FlushViewCountsResult {
        val deltas = boardViewCountPort.drainPendingDeltas()
        if (deltas.isEmpty()) return FlushViewCountsResult(boards = 0, updatedRows = 0, failed = 0)

        var updatedRows = 0
        var failed = 0
        for ((boardId, delta) in deltas) {
            try {
                updatedRows += boardRepositoryPort.addViewCount(boardId, delta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                log.error("Failed to flush view count (boardId={}, delta={}). Skip.", boardId, delta, e)
            }
        }

        return FlushViewCountsResult(boards = deltas.size, updatedRows = updatedRows, failed = failed)
            .also { log.info("view count flush finished: {}", it) }
    }
}
