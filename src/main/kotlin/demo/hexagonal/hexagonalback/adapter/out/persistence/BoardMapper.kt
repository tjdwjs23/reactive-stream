package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.domain.model.Board
import org.springframework.stereotype.Component

@Component
class BoardMapper {

    fun toEntity(domain: Board): BoardJpaEntity {
        return BoardJpaEntity(
            id = domain.id,
            title = domain.title,
            content = domain.content,
            createdAt = domain.createdAt
        )
    }

    fun toDomain(entity: BoardJpaEntity): Board {
        return Board(
            id = entity.id,
            title = entity.title,
            content = entity.content,
            createdAt = entity.createdAt
        )
    }
}
