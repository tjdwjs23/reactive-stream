package demo.board.application.service

import demo.board.application.port.`in`.FlushBoardViewCountsUseCase
import demo.board.application.port.`in`.FlushViewCountsResult
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardViewCountPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// Redis 버퍼의 조회수 델타를 DB로 write-back합니다.
// - Redis 버퍼를 스냅샷으로 옮겨두고(snapshot), 청크 단위 단일 UPDATE(배치)로 view_count에 더합니다.
// - 한 청크 실패가 전체를 막지 않도록 청크별로 내결함성 처리합니다(아카이브 배치와 동일한 철학).
//
// 왜 배치인가: 건별 UPDATE(addViewCount N회)는 DB 라운드트립이 N번 발생해, 플러시 대상이 수만 건이면
// 순차 왕복만으로 십수 초가 걸립니다. 청크당 단일 UPDATE(addViewCountsBatch)로 왕복을 N/chunkSize로 줄입니다.
//
// 내구성(commit-then-delete): 스냅샷을 곧바로 비우지 않고, 청크의 DB 반영이 성공한 뒤에만 그 청크를
// 버퍼에서 지웁니다(removeDrained). 반영 전에 죽으면 스냅샷이 남아 다음 플러시가 재시도하므로
// 델타가 유실되지 않습니다(유실 대신 크래시 시 약간의 중복 계수를 허용하는 at-least-once — 조회수는 근사값).
@Service
class FlushBoardViewCountsService(
    private val boardViewCountPort: BoardViewCountPort,
    private val boardRepositoryPort: BoardRepositoryPort,
    // 한 UPDATE에 묶을 게시글 수. 클수록 왕복↓(하지만 실패 시 재시도 단위↑·문장당 바인딩↑).
    @Value("\${board.view-count.flush-chunk-size:1000}") private val chunkSize: Int,
) : FlushBoardViewCountsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    // 스케줄 플러시와 admin 수동 플러시가 동시에 들어와도 스냅샷~반영~정리(DRAINING)가 서로 뒤엉키지
    // 않도록 플러시 전체를 프로세스 내에서 직렬화합니다(스냅샷과 removeDrained가 반드시 짝을 이루도록).
    // 여러 인스턴스 간 경합은 이 로컬 락으로 막지 못합니다 — 실무에선 Redis 분산 락/리더 선출을 씁니다.
    private val flushMutex = Mutex()

    override suspend fun flush(): FlushViewCountsResult =
        flushMutex.withLock {
            val deltas = boardViewCountPort.snapshotPendingDeltas()
            if (deltas.isEmpty()) return@withLock FlushViewCountsResult(boards = 0, updatedRows = 0, failed = 0)

            var updatedRows = 0
            var failed = 0
            for (chunk in deltas.entries.chunked(chunkSize)) {
                val chunkMap = chunk.associate { it.key to it.value }
                try {
                    updatedRows += boardRepositoryPort.addViewCountsBatch(chunkMap)
                    // commit-then-delete: DB 반영이 성공한 뒤에만 버퍼에서 지웁니다.
                    // 반영과 제거 사이에 죽으면 남아 다음 플러시가 재시도합니다(실패 청크도 지우지 않아 재시도됨).
                    boardViewCountPort.removeDrained(chunkMap.keys)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += chunkMap.size
                    log.error(
                        "Failed to flush view count chunk (size={}). Skip; will retry next flush.",
                        chunkMap.size,
                        e,
                    )
                }
            }

            FlushViewCountsResult(boards = deltas.size, updatedRows = updatedRows, failed = failed)
                .also { log.info("view count flush finished: {}", it) }
        }
}
