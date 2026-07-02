package demo.hexagonal.hexagonalback.application.port.out

import demo.hexagonal.hexagonalback.domain.model.Board
import kotlinx.coroutines.flow.Flow

// 도메인이 "나는 데이터를 저장하고 조회하고 싶어"라고 외치는 인터페이스입니다.
// 구체적으로 DB를 쓸지 파일시스템을 쓸지는 도메인은 모릅니다.
// R2DBC 논블로킹 스택이라 단건은 suspend, 다건은 Flow로 흘려보냅니다.
interface BoardRepositoryPort {
    suspend fun save(board: Board): Board

    suspend fun findById(id: Long): Board?

    fun findAll(): Flow<Board>

    suspend fun deleteById(id: Long)
}
