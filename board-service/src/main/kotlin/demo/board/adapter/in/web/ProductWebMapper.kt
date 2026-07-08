package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.ProductPage
import demo.board.application.port.out.ProductSearchHit
import demo.board.domain.model.Product
import org.springframework.stereotype.Component

// 도메인/포트 결과 ↔ 웹 DTO 변환. 컨트롤러는 유즈케이스 결과를 이 매퍼로만 응답 DTO로 바꿉니다.
@Component
class ProductWebMapper {
    fun toResponse(product: Product): ProductResponse =
        ProductResponse(
            id = requireNotNull(product.id) { "저장된 상품은 id가 있어야 합니다" },
            name = product.name,
            price = product.price,
            createdAt = product.createdAt,
        )

    fun toPageResponse(page: ProductPage): ProductPageResponse =
        ProductPageResponse(
            items = page.items.map(::toResponse),
            nextCursor = page.nextCursor,
            hasNext = page.hasNext,
        )

    // 검색/자동완성 히트를 응답으로. name은 하이라이트(<em>)가 있으면 그것을 우선합니다.
    fun toSearchResponse(
        keyword: String,
        hits: List<ProductSearchHit>,
    ): ProductSearchResponse =
        ProductSearchResponse(
            keyword = keyword,
            hits =
                hits.map { hit ->
                    ProductSearchItem(
                        id = requireNotNull(hit.product.id),
                        name = hit.highlightedName ?: hit.product.name,
                        price = hit.product.price,
                        score = hit.score,
                    )
                },
        )
}
