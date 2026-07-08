package demo.board.adapter.out.search

import demo.board.domain.model.Product
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime

// 실제 Elasticsearch(+Nori+ICU) 컨테이너로 상품 검색과 "초성 자동완성"을 검증합니다.
// 초성 자동완성이 이 프로젝트가 Spoqa 사례에서 도입한 핵심 — ICU로 완성형 음절과 사용자가 친 호환 자모(ㅅㄱ)를
// 모두 결합형 자모로 정규화해 초성만 뽑고, edge_ngram으로 접두 매칭합니다. 데이터는 alias 재색인 흐름으로 넣습니다.
@SpringBootTest
class ProductSearchAdapterTest(
    @Autowired private val adapter: ProductSearchAdapter,
    @Autowired private val operations: ElasticsearchOperations,
) : BehaviorSpec({

        fun refresh() = operations.indexOps(ProductDocument::class.java).refresh()

        fun seed(vararg products: Product) {
            val version = adapter.createNewVersionIndex()
            adapter.indexInto(products.toList(), version)
            adapter.promote(version)
            refresh()
        }

        fun p(
            id: Long,
            name: String,
        ) = Product(id = id, name = name, price = 1000, createdAt = LocalDateTime.now())

        Given("상품이 색인·승격돼 있을 때") {
            // 초성: 삼각김밥→ㅅㄱㄱㅂ, 삼계탕→ㅅㄱㅌ, 라면→ㄹㅁ
            seed(p(6001L, "삼각김밥"), p(6002L, "삼계탕"), p(6003L, "라면"))

            When("일반 검색 '김밥'") {
                val ids = adapter.search("김밥", 10).map { it.product.id }
                Then("Nori로 분해돼 삼각김밥이 매칭된다") {
                    ids shouldContain 6001L
                }
            }

            When("초성 자동완성 'ㅅㄱ'") {
                val ids = adapter.autocomplete("ㅅㄱ", 10).map { it.product.id }
                Then("초성이 ㅅㄱ로 시작하는 삼각김밥·삼계탕이 매칭되고, 라면은 아니다") {
                    ids shouldContain 6001L
                    ids shouldContain 6002L
                    ids shouldNotContain 6003L
                }
            }

            When("초성 자동완성 'ㅅㄱㄱ'(더 좁힘)") {
                val ids = adapter.autocomplete("ㅅㄱㄱ", 10).map { it.product.id }
                Then("삼각김밥만 매칭되고 삼계탕은 제외된다") {
                    ids shouldContain 6001L
                    ids shouldNotContain 6002L
                }
            }

            When("초성 자동완성 'ㄹ'") {
                val ids = adapter.autocomplete("ㄹ", 10).map { it.product.id }
                Then("라면만 매칭된다") {
                    ids shouldContain 6003L
                    ids shouldNotContain 6001L
                }
            }

            When("완성형 접두 '삼'으로 자동완성해도 초성으로 정규화된다") {
                val ids = adapter.autocomplete("삼", 10).map { it.product.id }
                Then("초성 ㅅ으로 시작하는 삼각김밥·삼계탕이 매칭된다") {
                    ids shouldContain 6001L
                    ids shouldContain 6002L
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = TestContainers.registerAll(registry)
    }
}
