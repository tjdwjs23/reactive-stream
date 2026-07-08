package demo.search.application.port.out

import demo.search.domain.model.Product

// 상품 정본 저장소 out-port. 저장 기술(JPA/PostgreSQL)은 어댑터가 감춥니다.
// 목록/재색인 순회는 Board와 동일한 키셋 페이지네이션(id 내림차순, cursor 미만).
interface ProductRepositoryPort {
    fun save(product: Product): Product

    fun findById(id: Long): Product?

    fun deleteById(id: Long)

    fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Product>
}
