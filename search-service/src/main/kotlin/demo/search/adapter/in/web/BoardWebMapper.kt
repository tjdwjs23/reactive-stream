package demo.search.adapter.`in`.web

import demo.search.application.port.`in`.BoardPage
import demo.search.application.port.out.BoardSearchHit
import demo.search.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardWebMapper {
    fun toResponse(board: Board): BoardResponse =
        BoardResponse(
            id = board.id!!, // 컨트롤러에 도달하는 Board는 항상 DB 저장 후 반환된 객체이므로 id가 null일 수 없습니다.
            title = board.title,
            content = board.content,
            createdAt = board.createdAt,
            viewCount = board.viewCount,
            authorId = board.authorId,
        )

    fun toPageResponse(page: BoardPage): BoardPageResponse =
        BoardPageResponse(
            items = page.items.map { toResponse(it) },
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )

    fun toSearchResponse(
        keyword: String,
        hits: List<BoardSearchHit>,
    ): BoardSearchResponse =
        BoardSearchResponse(
            keyword = keyword,
            total = hits.size,
            items =
                hits.map {
                    BoardSearchItemResponse(
                        board = toResponse(it.board),
                        score = it.score,
                        highlightedTitle = it.highlightedTitle,
                        highlightedContent = it.highlightedContent,
                    )
                },
        )
}
