package demo.search.adapter.out.persistence

import demo.search.events.ProductChangeType
import demo.search.events.ProductChangedEvent
import demo.search.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// product_outbox 어댑터의 기록/릴레이 조회/발행표시를 실제 Postgres로 검증합니다(이 스펙이 product_outbox의 유일한 writer).
@SpringBootTest
class ProductOutboxPersistenceAdapterTest(
    @Autowired private val adapter: ProductOutboxPersistenceAdapter,
) : BehaviorSpec({

        fun event(productId: Long) =
            ProductChangedEvent(
                eventId = UUID.randomUUID().toString(),
                productId = productId,
                type = ProductChangeType.CREATED,
                name = "상품$productId",
                price = productId,
                createdAt = LocalDateTime.now(),
                occurredAt = Instant.now(),
            )

        Given("미발행 이벤트를 기록하면") {
            val before = adapter.countUnpublished()
            adapter.record(event(1001L))
            adapter.record(event(1002L))
            adapter.record(event(1003L))

            When("countUnpublished / readUnpublished / markPublished를 수행하면") {
                Then("기록분이 미발행으로 집계되고, id 순으로 읽히며, 발행표시하면 미발행에서 빠진다") {
                    adapter.countUnpublished() shouldBe before + 3

                    val batch = adapter.readUnpublished(100)
                    // 이 스펙이 유일한 writer이므로 방금 기록한 3건이 id 오름차순으로 앞에 온다.
                    val ids = batch.map { it.id }
                    (ids == ids.sorted()) shouldBe true
                    batch.size shouldBe (before + 3).toInt()

                    adapter.markPublished(batch.take(2).map { it.id })
                    adapter.countUnpublished() shouldBe before + 1
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
