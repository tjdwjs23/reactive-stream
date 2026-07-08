package demo.search.adapter.out.persistence

import demo.search.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardMapper {
    fun toEntity(domain: Board): BoardJpaEntity =
        BoardJpaEntity(
            id = domain.id,
            title = domain.title,
            content = domain.content,
            createdAt = domain.createdAt,
            viewCount = domain.viewCount,
            authorId = domain.authorId,
        )

    fun toDomain(entity: BoardJpaEntity): Board =
        Board(
            id = entity.id,
            title = entity.title,
            content = entity.content,
            createdAt = entity.createdAt,
            viewCount = entity.viewCount,
            authorId = entity.authorId,
        )
}
