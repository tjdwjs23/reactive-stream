package demo.reactivestream.adapter.`in`.web

import demo.reactivestream.application.port.`in`.BoardPage
import demo.reactivestream.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardWebMapper {
    fun toResponse(board: Board): BoardResponse =
        BoardResponse(
            id = board.id!!, // 컨트롤러에 도달하는 Board는 항상 DB 저장 후 반환된 객체이므로 id가 null일 수 없습니다.
            title = board.title,
            content = board.content,
            createdAt = board.createdAt,
        )

    fun toPageResponse(page: BoardPage): BoardPageResponse =
        BoardPageResponse(
            items = page.items.map { toResponse(it) },
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )
}
