package demo.hexagonal.hexagonalback.application.port.out

import demo.hexagonal.hexagonalback.domain.model.Board
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
}
