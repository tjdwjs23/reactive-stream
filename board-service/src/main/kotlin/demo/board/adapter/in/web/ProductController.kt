package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.AutocompleteProductUseCase
import demo.board.application.port.`in`.CreateProductCommand
import demo.board.application.port.`in`.CreateProductUseCase
import demo.board.application.port.`in`.DeleteProductUseCase
import demo.board.application.port.`in`.GetProductUseCase
import demo.board.application.port.`in`.ProductAutocompleteQuery
import demo.board.application.port.`in`.ProductPageQuery
import demo.board.application.port.`in`.ProductSearchQuery
import demo.board.application.port.`in`.ReindexProductsUseCase
import demo.board.application.port.`in`.SearchProductUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

// 상품 API. 쓰기(생성/삭제/재색인)는 ROLE_ADMIN(SecurityConfig), 조회/검색/자동완성은 공개.
// UseCase 인터페이스를 개별 주입합니다(구현체 비의존). /api/products/search·/autocomplete·/search/reindex 리터럴
// 세그먼트는 /api/products/{id}(Long)보다 우선 매칭됩니다.
@Tag(name = "Product", description = "상품 CRUD + 한글 검색 + 초성 자동완성(Elasticsearch Nori/ICU)")
@RestController
@RequestMapping("/api/products")
class ProductController(
    private val createProductUseCase: CreateProductUseCase,
    private val getProductUseCase: GetProductUseCase,
    private val deleteProductUseCase: DeleteProductUseCase,
    private val searchProductUseCase: SearchProductUseCase,
    private val autocompleteProductUseCase: AutocompleteProductUseCase,
    private val reindexProductsUseCase: ReindexProductsUseCase,
    private val productWebMapper: ProductWebMapper,
) {
    @Operation(summary = "상품 생성", description = "이름/가격으로 상품을 만들고 201 Created를 반환합니다. ROLE_ADMIN이 필요합니다.")
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping
    fun create(
        @RequestBody request: CreateProductRequest,
    ): ResponseEntity<SuccessResponse<ProductResponse>> {
        val product =
            createProductUseCase.createProduct(
                CreateProductCommand(name = request.name, price = request.price),
            )
        val response = productWebMapper.toResponse(product)
        return SuccessResponse.created(response, URI.create("/api/products/${response.id}"))
    }

    @Operation(summary = "상품 단건 조회", description = "id로 상품을 조회합니다. 없으면 404.")
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
    ): ResponseEntity<SuccessResponse<ProductResponse>> =
        SuccessResponse.ok(productWebMapper.toResponse(getProductUseCase.getProduct(id)))

    @Operation(summary = "상품 목록(키셋 페이지네이션)", description = "id 내림차순 size건. 다음 페이지는 nextCursor를 cursor로 넘깁니다.")
    @GetMapping
    fun list(
        @Parameter(description = "마지막으로 본 상품 id. 생략 시 최신부터") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "페이지 크기(1~100)") @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<SuccessResponse<ProductPageResponse>> {
        val page = getProductUseCase.getProducts(ProductPageQuery(cursor = cursor, size = size))
        return SuccessResponse.ok(productWebMapper.toPageResponse(page))
    }

    @Operation(summary = "상품명 검색", description = "keyword를 Nori로 분석해 상품명을 검색하고 관련도순으로 반환합니다.")
    @GetMapping("/search")
    fun search(
        @Parameter(description = "검색어(공백 불가)") @RequestParam keyword: String,
        @Parameter(description = "결과 수(1~100)") @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<SuccessResponse<ProductSearchResponse>> {
        val hits = searchProductUseCase.search(ProductSearchQuery(keyword = keyword, size = size))
        return SuccessResponse.ok(productWebMapper.toSearchResponse(keyword, hits))
    }

    @Operation(
        summary = "상품 초성/접두 자동완성",
        description = "q에 초성(예: ㅅㄱ) 또는 접두 문자열을 주면 상품명을 좁혀 제안합니다(name.chosung, ICU 초성 + edge_ngram).",
    )
    @GetMapping("/autocomplete")
    fun autocomplete(
        @Parameter(description = "초성 또는 접두어(공백 불가)") @RequestParam q: String,
        @Parameter(description = "제안 수(1~50)") @RequestParam(defaultValue = "10") size: Int,
    ): ResponseEntity<SuccessResponse<ProductSearchResponse>> {
        val hits = autocompleteProductUseCase.autocomplete(ProductAutocompleteQuery(prefix = q, size = size))
        return SuccessResponse.ok(productWebMapper.toSearchResponse(q, hits))
    }

    @Operation(summary = "상품 삭제", description = "id로 상품을 삭제하고 204를 반환합니다. ROLE_ADMIN이 필요합니다.")
    @SecurityRequirement(name = "bearer-jwt")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        deleteProductUseCase.deleteProduct(id)
        return SuccessResponse.noContent()
    }

    @Operation(
        summary = "상품 전체 재색인(무중단, alias 스왑)",
        description = "DB(정본)를 새 버전 인덱스에 재구축한 뒤 'products' alias를 원자적으로 스왑합니다. ROLE_ADMIN이 필요합니다.",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/search/reindex")
    fun reindex(): ResponseEntity<SuccessResponse<ProductReindexResponse>> {
        val result = reindexProductsUseCase.reindexAll()
        return SuccessResponse.ok(
            ProductReindexResponse(reindexed = result.indexed, failed = result.failed, swapped = result.swapped),
        )
    }
}
