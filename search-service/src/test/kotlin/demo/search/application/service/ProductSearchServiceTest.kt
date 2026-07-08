package demo.search.application.service

import demo.search.application.port.`in`.ProductAutocompleteQuery
import demo.search.application.port.`in`.ProductSearchQuery
import demo.search.application.port.out.ProductRepositoryPort
import demo.search.application.port.out.ProductSearchHit
import demo.search.application.port.out.ProductSearchPort
import demo.search.domain.model.Product
import demo.search.support.NoOpProductObservabilityPort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.Collections

private class FakeProductRepo(
    private val products: List<Product>,
) : ProductRepositoryPort {
    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Product> =
        products
            .sortedByDescending { it.id!! }
            .filter { cursor == null || it.id!! < cursor }
            .take(limit)

    override fun save(product: Product) = throw UnsupportedOperationException()

    override fun findById(id: Long): Product? = throw UnsupportedOperationException()

    override fun deleteById(id: Long) = throw UnsupportedOperationException()
}

private class FakeProductSearchPort(
    private val failOnId: Long? = null,
    private val searchResult: List<ProductSearchHit> = emptyList(),
    private val autocompleteResult: List<ProductSearchHit> = emptyList(),
) : ProductSearchPort {
    val indexedIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    @Volatile var createdIndex: String? = null

    @Volatile var promotedIndex: String? = null

    @Volatile var discardedIndex: String? = null

    override fun search(
        keyword: String,
        size: Int,
    ) = searchResult

    override fun autocomplete(
        prefix: String,
        size: Int,
    ) = autocompleteResult

    override fun createNewVersionIndex(): String {
        val name = "products_test_${System.identityHashCode(this)}"
        createdIndex = name
        return name
    }

    override fun indexInto(
        products: List<Product>,
        indexName: String,
    ): Int {
        if (failOnId != null && products.any { it.id == failOnId }) {
            throw IllegalStateException("forced failure on page containing id=$failOnId")
        }
        products.forEach { indexedIds.add(it.id!!) }
        return products.size
    }

    override fun promote(indexName: String) {
        promotedIndex = indexName
    }

    override fun deleteVersionIndex(indexName: String) {
        discardedIndex = indexName
    }
}

private fun product(id: Long) = Product(id = id, name = "상품$id", price = id, createdAt = LocalDateTime.now())

private const val PAGE_SIZE = 500

class ProductSearchServiceTest :
    BehaviorSpec({

        Given("검색/자동완성은 포트 결과를 그대로 반환한다") {
            val hits = listOf(ProductSearchHit(product(1L), score = 1.0, highlightedName = "<em>삼</em>각김밥"))
            val ac = listOf(ProductSearchHit(product(2L), score = 0.5, highlightedName = null))
            val service =
                ProductSearchService(
                    FakeProductSearchPort(searchResult = hits, autocompleteResult = ac),
                    FakeProductRepo(emptyList()),
                    NoOpProductObservabilityPort,
                )

            When("search / autocomplete를 호출하면") {
                Then("각 포트 결과를 그대로 반환한다") {
                    service
                        .search(
                            ProductSearchQuery(keyword = "김밥", size = 10),
                        ).map { it.product.id } shouldContainExactly
                        listOf(1L)
                    service
                        .autocomplete(
                            ProductAutocompleteQuery(prefix = "ㅅㄱ", size = 10),
                        ).map { it.product.id } shouldContainExactly
                        listOf(2L)
                }
            }
        }

        Given("전체 재색인 - 전량 성공") {
            val products = (1L..(PAGE_SIZE + 1L)).map { product(it) }
            val searchPort = FakeProductSearchPort()
            val service = ProductSearchService(searchPort, FakeProductRepo(products), NoOpProductObservabilityPort)

            When("reindexAll을 실행하면") {
                val result = service.reindexAll()

                Then("새 버전 인덱스에 전량 색인 후 alias를 스왑한다") {
                    result.indexed shouldBe (PAGE_SIZE + 1L)
                    result.failed shouldBe 0L
                    result.swapped shouldBe true
                    searchPort.promotedIndex shouldBe searchPort.createdIndex
                    searchPort.discardedIndex.shouldBeNull()
                }
            }
        }

        Given("전체 재색인 - 일부 페이지 실패") {
            val products = (1L..(PAGE_SIZE + 1L)).map { product(it) }
            val searchPort = FakeProductSearchPort(failOnId = 300L)
            val service = ProductSearchService(searchPort, FakeProductRepo(products), NoOpProductObservabilityPort)

            When("reindexAll을 실행하면") {
                val result = service.reindexAll()

                Then("불완전 인덱스를 폐기하고 스왑하지 않는다(자동 롤백)") {
                    result.failed shouldBe PAGE_SIZE.toLong()
                    result.indexed shouldBe 1L
                    result.swapped shouldBe false
                    searchPort.promotedIndex.shouldBeNull()
                    searchPort.discardedIndex shouldBe searchPort.createdIndex
                }
            }
        }

        Given("전체 재색인 - 상품이 없을 때") {
            val searchPort = FakeProductSearchPort()
            val service = ProductSearchService(searchPort, FakeProductRepo(emptyList()), NoOpProductObservabilityPort)

            When("reindexAll을 실행하면") {
                val result = service.reindexAll()

                Then("빈 새 인덱스를 스왑한다") {
                    result.indexed shouldBe 0L
                    result.failed shouldBe 0L
                    result.swapped shouldBe true
                    searchPort.promotedIndex shouldBe searchPort.createdIndex
                }
            }
        }
    })
