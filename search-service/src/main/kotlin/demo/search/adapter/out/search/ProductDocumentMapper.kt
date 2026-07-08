package demo.search.adapter.out.search

import demo.search.domain.model.Product
import org.springframework.stereotype.Component

// 도메인 Product ↔ ES 문서(ProductDocument) 변환의 유일한 지점. 도메인 모델엔 ES 애노테이션이 새어 들어가지 않습니다.
@Component
class ProductDocumentMapper {
    fun toDocument(domain: Product): ProductDocument =
        ProductDocument(
            id = domain.id!!.toString(),
            name = domain.name,
            price = domain.price,
            createdAt = domain.createdAt,
        )

    fun toDomain(document: ProductDocument): Product =
        Product(
            id = document.id.toLong(),
            name = document.name,
            price = document.price,
            createdAt = document.createdAt,
        )
}
