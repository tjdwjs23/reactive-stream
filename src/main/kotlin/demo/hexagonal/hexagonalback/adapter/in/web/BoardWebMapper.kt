package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardWebMapper {

    fun toResponse(board: Board): BoardResponse = BoardResponse(
        id = board.id!!,
        title = board.title,
        content = board.content,
        createdAt = board.createdAt
    )

    fun toResponseList(boards: List<Board>): List<BoardResponse> =
        boards.map { toResponse(it) }
}
