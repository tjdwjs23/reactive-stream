package demo.search.adapter.out.persistence

import demo.search.application.port.out.ProductRepositoryPort
import demo.search.domain.model.Product
import org.springframework.stereotype.Repository

// ProductRepositoryPort의 JPA 구현. 기본 CRUD는 Spring Data JPA, 목록은 Kotlin JDSL 키셋(id 내림차순, cursor 미만).
// save는 transactionRunner.execute { } 안에서 호출돼 아웃박스 기록과 같은 트랜잭션으로 묶입니다(Board와 동일).
@Repository
class ProductPersistenceAdapter(
    private val productJpaRepository: ProductJpaRepository,
    private val productMapper: ProductMapper,
) : ProductRepositoryPort {
    override fun save(product: Product): Product =
        productMapper.toDomain(productJpaRepository.save(productMapper.toEntity(product)))

    override fun findById(id: Long): Product? =
        productJpaRepository.findById(id).orElse(null)?.let(productMapper::toDomain)

    override fun deleteById(id: Long) {
        productJpaRepository.deleteById(id)
    }

    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Product> =
        productJpaRepository
            .findAll(limit = limit) {
                select(entity(ProductJpaEntity::class))
                    .from(entity(ProductJpaEntity::class))
                    .whereAnd(cursor?.let { path(ProductJpaEntity::id).lessThan(it) })
                    .orderBy(path(ProductJpaEntity::id).desc())
            }.filterNotNull()
            .map(productMapper::toDomain)
}
