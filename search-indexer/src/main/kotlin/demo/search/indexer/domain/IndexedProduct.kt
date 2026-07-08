package demo.search.indexer.domain

import java.time.LocalDateTime

// 색인 대상 상품의 순수 표현(IndexedBoard와 대칭). 이 서비스는 "색인"에만 관심이 있어 필요한 필드만 가집니다.
// ES 애노테이션은 여기 새어 들어오지 않습니다(어댑터의 ProductDocument로 변환).
data class IndexedProduct(
    val id: Long,
    val name: String,
    val price: Long,
    val createdAt: LocalDateTime,
)
