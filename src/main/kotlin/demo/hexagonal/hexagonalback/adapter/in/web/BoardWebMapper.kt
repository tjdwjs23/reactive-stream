package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardWebMapper {

    fun toResponse(board: Board): BoardResponse = BoardResponse(
        id = board.id!!, // 컨트롤러에 도달하는 Board는 항상 DB 저장 후 반환된 객체이므로 id가 null일 수 없습니다.
        title = board.title,
        content = board.content,
        createdAt = board.createdAt
    )

    fun toResponseList(boards: List<Board>): List<BoardResponse> =
        boards.map { toResponse(it) }
}
