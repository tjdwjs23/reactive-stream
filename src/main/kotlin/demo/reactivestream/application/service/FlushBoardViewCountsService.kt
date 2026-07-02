package demo.reactivestream.application.service

import demo.reactivestream.application.port.`in`.FlushBoardViewCountsUseCase
import demo.reactivestream.application.port.`in`.FlushViewCountsResult
import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.application.port.out.BoardViewCountPort
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// Redis 버퍼의 조회수 델타를 DB로 write-back합니다.
// - Redis에서 원자적으로 델타를 꺼내고(drain), 청크 단위 단일 UPDATE(배치)로 view_count에 더합니다.
// - 한 청크 실패가 전체를 막지 않도록 청크별로 내결함성 처리합니다(아카이브 배치와 동일한 철학).
//
// 왜 배치인가: 건별 UPDATE(addViewCount N회)는 DB 라운드트립이 N번 발생해, 플러시 대상이 수만 건이면
// 순차 왕복만으로 십수 초가 걸립니다. 청크당 단일 UPDATE(addViewCountsBatch)로 왕복을 N/chunkSize로 줄입니다.
//
// 주의(학습용 단순화): drain 이후 DB 반영 전에 프로세스가 죽으면 그 델타는 유실됩니다.
// 실무에선 반영 성공분만 커밋 후 삭제하거나, 실패분을 다시 버퍼로 되돌리는 보상 로직을 둡니다.
@Service
class FlushBoardViewCountsService(
    private val boardViewCountPort: BoardViewCountPort,
    private val boardRepositoryPort: BoardRepositoryPort,
    // 한 UPDATE에 묶을 게시글 수. 클수록 왕복↓(하지만 실패 시 재시도 단위↑·문장당 바인딩↑).
    @Value("\${board.view-count.flush-chunk-size:1000}") private val chunkSize: Int,
) : FlushBoardViewCountsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun flush(): FlushViewCountsResult {
        val deltas = boardViewCountPort.drainPendingDeltas()
        if (deltas.isEmpty()) return FlushViewCountsResult(boards = 0, updatedRows = 0, failed = 0)

        var updatedRows = 0
        var failed = 0
        for (chunk in deltas.entries.chunked(chunkSize)) {
            val chunkMap = chunk.associate { it.key to it.value }
            try {
                updatedRows += boardRepositoryPort.addViewCountsBatch(chunkMap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += chunkMap.size
                log.error("Failed to flush view count chunk (size={}). Skip.", chunkMap.size, e)
            }
        }

        return FlushViewCountsResult(boards = deltas.size, updatedRows = updatedRows, failed = failed)
            .also { log.info("view count flush finished: {}", it) }
    }
}
