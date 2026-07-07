package demo.board.application.port.out

import demo.board.domain.model.Board
import kotlinx.coroutines.flow.Flow

// 도메인이 "나는 데이터를 저장하고 조회하고 싶어"라고 외치는 인터페이스입니다.
// 구체적으로 DB를 쓸지 파일시스템을 쓸지는 도메인은 모릅니다.
// R2DBC 논블로킹 스택이라 단건은 suspend, 다건은 Flow로 흘려보냅니다.
interface BoardRepositoryPort {
    suspend fun save(board: Board): Board

    suspend fun findById(id: Long): Board?

    // 키셋 페이지네이션 조회: cursor(마지막으로 본 id) 이전(과거) 데이터를 id 내림차순으로 최대 limit건.
    // cursor가 null이면 최신부터. hasNext 판정을 위해 호출 측이 limit을 size+1로 넘길 수 있습니다.
    fun findPage(
        cursor: Long?,
        limit: Int,
    ): Flow<Board>

    suspend fun deleteById(id: Long)

    // 조회수 write-back(배치): 여러 게시글의 view_count 델타를 단일 UPDATE로 한꺼번에 반영합니다.
    // 건별 왕복 대신 DB 라운드트립을 1회로 줄여, 플러시 대상이 많을수록 큰 이득입니다.
    // 반환값은 영향받은 행 수(존재하지 않는 id는 반영되지 않으므로 deltas.size보다 작을 수 있음).
    suspend fun addViewCountsBatch(deltas: Map<Long, Long>): Int
}
