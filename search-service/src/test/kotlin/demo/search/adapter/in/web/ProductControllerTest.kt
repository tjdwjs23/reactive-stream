package demo.search.adapter.`in`.web

import demo.search.application.port.`in`.AutocompleteProductUseCase
import demo.search.application.port.`in`.CreateProductUseCase
import demo.search.application.port.`in`.DeleteProductUseCase
import demo.search.application.port.`in`.GetProductUseCase
import demo.search.application.port.`in`.ProductPage
import demo.search.application.port.`in`.ProductReindexResult
import demo.search.application.port.`in`.ReindexProductsUseCase
import demo.search.application.port.`in`.SearchProductUseCase
import demo.search.application.port.out.ProductSearchHit
import demo.search.domain.model.Product
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

// 접근 통제(ROLE_ADMIN)는 SecurityConfig가 담당하며 SecurityIntegrationTest가 검증합니다.
// 여기서는 standalone MockMvc로 핸들러 + DTO + ProductWebMapper 동작만 봅니다.
private class ProductControllerFixture {
    val createUseCase = mockk<CreateProductUseCase>()
    val getUseCase = mockk<GetProductUseCase>()
    val deleteUseCase = mockk<DeleteProductUseCase>(relaxed = true)
    val searchUseCase = mockk<SearchProductUseCase>()
    val autocompleteUseCase = mockk<AutocompleteProductUseCase>()
    val reindexUseCase = mockk<ReindexProductsUseCase>()

    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ProductController(
                    createUseCase,
                    getUseCase,
                    deleteUseCase,
                    searchUseCase,
                    autocompleteUseCase,
                    reindexUseCase,
                    ProductWebMapper(),
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
            .build()
}

private fun product(
    id: Long,
    name: String,
) = Product(id = id, name = name, price = 1000, createdAt = LocalDateTime.parse("2026-07-08T00:00:00"))

class ProductControllerTest :
    BehaviorSpec({

        Given("상품 생성 - POST /api/products") {
            val f = ProductControllerFixture()
            every { f.createUseCase.createProduct(any()) } returns product(1L, "삼각김밥")

            When("생성 요청하면") {
                Then("201 Created + Location + 생성 상품을 반환한다") {
                    f.mockMvc
                        .perform(
                            post("/api/products")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"name":"삼각김밥","price":1200}"""),
                        ).andExpect(status().isCreated)
                        .andExpect(jsonPath("$.result.id").value(1))
                        .andExpect(jsonPath("$.result.name").value("삼각김밥"))
                }
            }
        }

        Given("상품 단건/목록 조회 - GET") {
            val f = ProductControllerFixture()
            every { f.getUseCase.getProduct(1L) } returns product(1L, "김밥")
            every { f.getUseCase.getProducts(any()) } returns
                ProductPage(items = listOf(product(2L, "라면"), product(1L, "김밥")), nextCursor = 1L, hasNext = true)

            When("단건 조회하면") {
                Then("200 + 상품") {
                    f.mockMvc
                        .perform(get("/api/products/1"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.name").value("김밥"))
                }
            }

            When("목록 조회하면") {
                Then("200 + items + nextCursor + hasNext") {
                    f.mockMvc
                        .perform(get("/api/products?size=2"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.items.length()").value(2))
                        .andExpect(jsonPath("$.result.nextCursor").value(1))
                        .andExpect(jsonPath("$.result.hasNext").value(true))
                }
            }
        }

        Given("검색/자동완성 - GET") {
            val f = ProductControllerFixture()
            every { f.searchUseCase.search(any()) } returns
                listOf(ProductSearchHit(product(1L, "삼각김밥"), score = 2.0, highlightedName = "<em>삼각</em>김밥"))
            every { f.autocompleteUseCase.autocomplete(any()) } returns
                listOf(ProductSearchHit(product(1L, "삼각김밥"), score = 1.0, highlightedName = null))

            When("검색하면") {
                Then("200 + 하이라이트 이름") {
                    f.mockMvc
                        .perform(get("/api/products/search?keyword=김밥"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.hits[0].name").value("<em>삼각</em>김밥"))
                }
            }

            When("초성 자동완성(q=ㅅㄱ)하면") {
                Then("200 + 하이라이트 없으면 원문 이름") {
                    f.mockMvc
                        .perform(get("/api/products/autocomplete?q=ㅅㄱ"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.hits[0].name").value("삼각김밥"))
                }
            }
        }

        Given("삭제/재색인 - POST·DELETE") {
            val f = ProductControllerFixture()
            every { f.reindexUseCase.reindexAll() } returns
                ProductReindexResult(indexed = 10, failed = 0, swapped = true)

            When("삭제하면") {
                Then("204 No Content") {
                    f.mockMvc.perform(delete("/api/products/1")).andExpect(status().isNoContent)
                    verify { f.deleteUseCase.deleteProduct(1L) }
                }
            }

            When("재색인하면") {
                Then("200 + reindexed/failed/swapped") {
                    f.mockMvc
                        .perform(post("/api/products/search/reindex"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.reindexed").value(10))
                        .andExpect(jsonPath("$.result.swapped").value(true))
                }
            }
        }
    })
