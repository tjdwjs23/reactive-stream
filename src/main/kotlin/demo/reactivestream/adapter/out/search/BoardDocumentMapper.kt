package demo.reactivestream.adapter.out.search

import demo.reactivestream.domain.model.Board
import org.springframework.stereotype.Component

// 도메인 Board ↔ ES 문서(BoardDocument) 변환의 유일한 지점.
// R2DBC와 마찬가지로, 도메인 모델에는 ES 애노테이션이 절대 새어 들어가지 않습니다.
@Component
class BoardDocumentMapper {
    // 색인 대상 Board는 항상 저장 후 객체이므로 id가 null이 아닙니다. ES _id로는 문자열 id를 씁니다.
    fun toDocument(domain: Board): BoardDocument =
        BoardDocument(
            id = domain.id!!.toString(),
            title = domain.title,
            content = domain.content,
            createdAt = domain.createdAt,
            viewCount = domain.viewCount,
        )

    fun toDomain(document: BoardDocument): Board =
        Board(
            id = document.id.toLong(),
            title = document.title,
            content = document.content,
            createdAt = document.createdAt,
            viewCount = document.viewCount,
        )
}
