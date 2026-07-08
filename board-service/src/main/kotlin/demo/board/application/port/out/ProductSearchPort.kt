package demo.board.application.port.out

import demo.board.domain.model.Product

// 상품 검색 인덱스 out-port(Elasticsearch). Board와 동일한 alias 기반 무중단 재색인 계약을 가집니다:
// 검색/자동완성은 alias('products')로 읽고, 재색인은 새 버전 인덱스 재구축 → 원자적 alias 스왑.
// 단건 색인/삭제는 이벤트 소비자(search-indexer)가 alias에 반영하므로 이 포트엔 없습니다.
interface ProductSearchPort {
    // 상품명 전문검색(Nori). 관련도(_score) 내림차순 최대 size건.
    fun search(
        keyword: String,
        size: Int,
    ): List<ProductSearchHit>

    // 초성/접두 자동완성. name.chosung(초성 색인 + edge_ngram) 필드에 prefix 매칭해 상위 size건.
    fun autocomplete(
        prefix: String,
        size: Int,
    ): List<ProductSearchHit>

    // ── Alias 기반 무중단 재색인(Board와 동일 패턴) ──
    fun createNewVersionIndex(): String

    fun indexInto(
        products: List<Product>,
        indexName: String,
    ): Int

    fun promote(indexName: String)

    fun deleteVersionIndex(indexName: String)
}

// 상품 검색 한 건의 결과. 도메인 Product + 관련도 점수 + 이름 하이라이트(<em>).
data class ProductSearchHit(
    val product: Product,
    val score: Double,
    val highlightedName: String?,
)
