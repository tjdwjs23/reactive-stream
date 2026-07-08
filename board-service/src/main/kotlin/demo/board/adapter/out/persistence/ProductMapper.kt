package demo.board.adapter.out.persistence

import demo.board.domain.model.Product
import org.springframework.stereotype.Component

@Component
class ProductMapper {
    fun toEntity(domain: Product): ProductJpaEntity =
        ProductJpaEntity(
            id = domain.id,
            name = domain.name,
            price = domain.price,
            createdAt = domain.createdAt,
        )

    fun toDomain(entity: ProductJpaEntity): Product =
        Product(
            id = entity.id,
            name = entity.name,
            price = entity.price,
            createdAt = entity.createdAt,
        )
}
