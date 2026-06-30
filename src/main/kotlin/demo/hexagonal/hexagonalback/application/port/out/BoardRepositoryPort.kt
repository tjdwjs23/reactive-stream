package demo.hexagonal.hexagonalback.application.port.out

import demo.hexagonal.hexagonalback.domain.model.Board

// 도메인이 "나는 데이터를 저장하고 조회하고 싶어"라고 외치는 인터페이스입니다.
// 구체적으로 DB를 쓸지 파일시스템을 쓸지는 도메인은 모릅니다.
interface BoardRepositoryPort {
    fun save(board: Board): Board

    fun findById(id: Long): Board?

    fun findAll(): List<Board>

    fun deleteById(id: Long)
}
