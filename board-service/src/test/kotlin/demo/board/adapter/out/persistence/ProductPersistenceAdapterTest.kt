package demo.board.adapter.out.persistence

import demo.board.domain.model.Product
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime

@SpringBootTest
class ProductPersistenceAdapterTest(
    @Autowired private val adapter: ProductPersistenceAdapter,
) : BehaviorSpec({

        Given("상품 저장/조회/삭제") {
            When("save하면") {
                val saved = adapter.save(Product(name = "삼각김밥", price = 1200, createdAt = LocalDateTime.now()))

                Then("id가 채번된다") {
                    saved.id.shouldNotBeNull()
                    saved.name shouldBe "삼각김밥"
                    saved.price shouldBe 1200L
                }
            }

            When("findById / 없는 id 조회") {
                val saved = adapter.save(Product(name = "김밥", price = 3000, createdAt = LocalDateTime.now()))

                Then("존재하면 반환, 없으면 null") {
                    adapter.findById(saved.id!!)?.name shouldBe "김밥"
                    adapter.findById(-1L).shouldBeNull()
                }
            }

            When("deleteById하면") {
                val saved = adapter.save(Product(name = "삭제상품", price = 1, createdAt = LocalDateTime.now()))
                adapter.deleteById(saved.id!!)

                Then("더 이상 조회되지 않는다") {
                    adapter.findById(saved.id!!).shouldBeNull()
                }
            }
        }

        Given("키셋 페이지네이션 - findPage") {
            val ids =
                (1..5).map {
                    adapter
                        .save(
                            Product(name = "page-$it", price = it.toLong(), createdAt = LocalDateTime.now()),
                        ).id!!
                }
            val idSet = ids.toSet()

            When("cursor를 따라가며 size=2로 반복 조회하면") {
                val collected = mutableListOf<Long>()
                var cursor: Long? = null
                var guard = 0
                while (guard++ < 10_000) {
                    val page = adapter.findPage(cursor, 2)
                    if (page.isEmpty()) break
                    (page.size <= 2) shouldBe true
                    page.forEach { if (it.id in idSet) collected.add(it.id!!) }
                    cursor = page.last().id
                }

                Then("우리 id가 id 내림차순으로 모두 반환된다") {
                    collected shouldContainExactly ids.sortedDescending()
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
