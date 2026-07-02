package demo.reactivestream.adapter.out.persistence

import demo.reactivestream.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardMapper {
    fun toEntity(domain: Board): BoardR2dbcEntity =
        BoardR2dbcEntity(
            id = domain.id,
            title = domain.title,
            content = domain.content,
            createdAt = domain.createdAt,
            viewCount = domain.viewCount,
        )

    fun toDomain(entity: BoardR2dbcEntity): Board =
        Board(
            id = entity.id,
            title = entity.title,
            content = entity.content,
            createdAt = entity.createdAt,
            viewCount = entity.viewCount,
        )
}
