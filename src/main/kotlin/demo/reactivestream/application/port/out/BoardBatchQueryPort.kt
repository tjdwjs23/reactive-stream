package demo.reactivestream.application.port.out

import demo.reactivestream.domain.model.Board
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

// 대용량 배치 전용 out-port입니다. 일반 CRUD용 BoardRepositoryPort와 분리해(ISP)
// "전체를 List로 올리는" findAll()이 배치 경로로 새어 들어오지 않게 합니다.
interface BoardBatchQueryPort {
    // 스트리밍 조회: 전체를 메모리에 올리지 않고 pageSize 단위로 흘려보냅니다.
    // (murray UserReader가 Redis Cursor로 하던 것을 키셋 페이지네이션으로 구현)
    // R2DBC 논블로킹 스트림이라 Sequence 대신 Flow로 흘려보내며, 소비자가 밀리면
    // Flow의 백프레셔로 생산자가 다음 페이지를 읽지 않도록 자연히 눌립니다.
    fun findStaleBoards(
        before: LocalDateTime,
        pageSize: Int,
    ): Flow<Board>

    // 청크 단위 벌크 삭제. 반환값은 실제 삭제된 행 수입니다.
    suspend fun deleteByIds(ids: List<Long>): Int
}
