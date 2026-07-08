package demo.search.adapter.out.search

import demo.search.domain.model.Product
import demo.search.support.TestContainers
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
            // 초성: 삼각김밥→ㅅㄱㄱㅂ, 삼계탕→ㅅㄱㅌ, 라면→ㄹㅁ, 사과→ㅅㄱ
            seed(p(6001L, "삼각김밥"), p(6002L, "삼계탕"), p(6003L, "라면"), p(6004L, "사과"))

            When("일반 검색 '김밥'") {
                val ids = adapter.search("김밥", 10).map { it.product.id }
                Then("Nori로 분해돼 삼각김밥이 매칭된다") {
                    ids shouldContain 6001L
                }
            }

            When("순수 초성 'ㅅㄱ'") {
                val ids = adapter.autocomplete("ㅅㄱ", 10).map { it.product.id }
                Then("초성이 ㅅㄱ로 시작하는 삼각김밥·삼계탕·사과가 모두 매칭되고 라면은 아니다") {
                    ids shouldContain 6001L
                    ids shouldContain 6002L
                    ids shouldContain 6004L
                    ids shouldNotContain 6003L
                }
            }

            When("순수 초성 'ㅅㄱㄱ'(더 좁힘)") {
                val ids = adapter.autocomplete("ㅅㄱㄱ", 10).map { it.product.id }
                Then("초성 ㅅㄱㄱ은 삼각김밥만(삼계탕·사과 제외)") {
                    ids shouldContain 6001L
                    ids shouldNotContain 6002L
                    ids shouldNotContain 6004L
                }
            }

            When("순수 초성 'ㄹ'") {
                val ids = adapter.autocomplete("ㄹ", 10).map { it.product.id }
                Then("라면만 매칭된다") {
                    ids shouldContain 6003L
                    ids shouldNotContain 6001L
                }
            }

            // ── 버그 수정 검증: 완성형 음절은 초성으로 뭉개지지 않고 자모 접두로 매칭된다 ──
            When("완성형+초성 혼합 '사ㄱ'") {
                val ids = adapter.autocomplete("사ㄱ", 10).map { it.product.id }
                Then("'사'로 시작하고 다음이 ㄱ초성인 사과만 — 삼계탕·삼각김밥은 제외된다") {
                    ids shouldContain 6004L
                    ids shouldNotContain 6001L
                    ids shouldNotContain 6002L
                }
            }

            When("완성형 '삼'") {
                val ids = adapter.autocomplete("삼", 10).map { it.product.id }
                Then("'삼'으로 시작하는 삼계탕·삼각김밥만 — 사과(ㅅㄱ)는 제외된다") {
                    ids shouldContain 6001L
                    ids shouldContain 6002L
                    ids shouldNotContain 6004L
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
