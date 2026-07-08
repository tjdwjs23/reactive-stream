package demo.board.application.port.`in`

import demo.board.application.port.out.ProductSearchHit
import demo.board.domain.model.Product

// 상품 유즈케이스(in-port)들과 커맨드/쿼리. Board와 달리 소유권이 없어 커맨드에 requester 정보가 없습니다
// (쓰기는 ROLE_ADMIN으로 SecurityConfig가 통제). 다건 조회는 포트 관례대로 List/Page로 반환합니다.

// 1. 상품 생성(관리자). 자가 검증 커맨드 — 생성 시점(컨트롤러)에서 require로 불변식을 검사해 서비스엔 항상 유효값이 옵니다.
interface CreateProductUseCase {
    fun createProduct(command: CreateProductCommand): Product
}

data class CreateProductCommand(
    val name: String,
    val price: Long,
) {
    init {
        require(name.isNotBlank()) { "상품명은 비어있을 수 없습니다." }
        require(name.length <= Product.MAX_NAME_LENGTH) { "상품명은 ${Product.MAX_NAME_LENGTH}자를 넘을 수 없습니다." }
        require(price >= 0) { "가격은 0 이상이어야 합니다." }
    }
}

// 2. 상품 삭제(관리자).
interface DeleteProductUseCase {
    fun deleteProduct(id: Long)
}

// 3. 상품 조회(단건/목록, 공개). 목록은 키셋 페이지네이션(Board와 동일 관례).
interface GetProductUseCase {
    fun getProduct(id: Long): Product

    fun getProducts(query: ProductPageQuery): ProductPage
}

data class ProductPageQuery(
    val cursor: Long? = null,
    val size: Int = 20,
) {
    init {
        require(size in 1..100) { "size는 1에서 100 사이여야 합니다." }
    }
}

data class ProductPage(
    val items: List<Product>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

// 4. 상품 검색(공개). 상품명 전문검색(Nori). 다건은 관례대로 List<ProductSearchHit>.
interface SearchProductUseCase {
    fun search(query: ProductSearchQuery): List<ProductSearchHit>
}

data class ProductSearchQuery(
    val keyword: String,
    val size: Int = 20,
) {
    init {
        require(keyword.isNotBlank()) { "검색어는 비어있을 수 없습니다." }
        require(size in 1..100) { "size는 1에서 100 사이여야 합니다." }
    }
}

// 5. 상품 자동완성(공개). 초성(예: "ㅅㄱ") 또는 접두어로 상품명을 좁혀 제안합니다.
interface AutocompleteProductUseCase {
    fun autocomplete(query: ProductAutocompleteQuery): List<ProductSearchHit>
}

data class ProductAutocompleteQuery(
    // 초성("ㅅㄱ") 또는 접두 문자열. 공백만 아니면 허용(초성 한 글자부터).
    val prefix: String,
    val size: Int = 10,
) {
    init {
        require(prefix.isNotBlank()) { "자동완성 접두어는 비어있을 수 없습니다." }
        require(size in 1..50) { "size는 1에서 50 사이여야 합니다." }
    }
}

// 6. 전체 재색인(관리자). Board와 동일하게 새 버전 인덱스에 재구축 후 alias 스왑(무중단).
interface ReindexProductsUseCase {
    fun reindexAll(): ProductReindexResult
}

data class ProductReindexResult(
    val indexed: Long,
    val failed: Long,
    val swapped: Boolean,
)
