package demo.search.adapter.`in`.web

import java.time.LocalDateTime

// 상품 API 요청/응답 DTO. 도메인/이벤트 모델과 분리된 웹 표현입니다(변환은 ProductWebMapper).

data class CreateProductRequest(
    val name: String,
    val price: Long,
)

data class ProductResponse(
    val id: Long,
    val name: String,
    val price: Long,
    val createdAt: LocalDateTime,
)

data class ProductPageResponse(
    val items: List<ProductResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

// 검색/자동완성 결과 한 건. name은 매칭 하이라이트(<em>)가 있으면 그걸, 없으면 원문을 담습니다.
data class ProductSearchItem(
    val id: Long,
    val name: String,
    val price: Long,
    val score: Double,
)

data class ProductSearchResponse(
    val keyword: String,
    val hits: List<ProductSearchItem>,
)

data class ProductReindexResponse(
    val reindexed: Long,
    val failed: Long,
    val swapped: Boolean,
)
