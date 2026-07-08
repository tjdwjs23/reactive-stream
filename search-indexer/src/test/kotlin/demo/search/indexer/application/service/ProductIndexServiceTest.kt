package demo.search.indexer.application.service

import demo.search.events.ProductChangeType
import demo.search.events.ProductChangedEvent
import demo.search.indexer.application.port.out.ProductIndexPort
import demo.search.indexer.application.port.out.ProductIndexerObservabilityPort
import demo.search.indexer.domain.IndexedProduct
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class ProductIndexServiceTest :
    BehaviorSpec({

        fun event(
            productId: Long,
            type: ProductChangeType,
            name: String? = "상품$productId",
            price: Long? = productId,
        ) = ProductChangedEvent(
            eventId = UUID.randomUUID().toString(),
            productId = productId,
            type = type,
            name = name,
            price = price,
            createdAt = if (type == ProductChangeType.DELETED) null else LocalDateTime.now(),
            occurredAt = Instant.now(),
        )

        fun newService(port: ProductIndexPort): ProductIndexService {
            val obs = mockk<ProductIndexerObservabilityPort>(relaxed = true)
            // recordIndexingBatch는 넘긴 블록을 실제로 실행해야 한다.
            every { obs.recordIndexingBatch(any()) } answers { firstArg<() -> Unit>().invoke() }
            return ProductIndexService(port, obs)
        }

        Given("CREATED/UPDATED와 DELETED가 섞인 배치") {
            val port = mockk<ProductIndexPort>(relaxed = true)
            val service = newService(port)

            When("applyAll을 호출하면") {
                service.applyAll(
                    listOf(
                        event(1L, ProductChangeType.CREATED),
                        event(2L, ProductChangeType.UPDATED),
                        event(3L, ProductChangeType.DELETED),
                    ),
                )

                Then("CREATED/UPDATED는 upsert, DELETED는 삭제로 라우팅된다") {
                    val saved = slot<List<IndexedProduct>>()
                    verify { port.saveAll(capture(saved)) }
                    saved.captured.map { it.id }.sorted() shouldBe listOf(1L, 2L)
                    verify { port.deleteAllById(listOf(3L)) }
                }
            }
        }

        Given("같은 상품에 여러 이벤트(마지막이 이긴다)") {
            val port = mockk<ProductIndexPort>(relaxed = true)
            val service = newService(port)

            When("CREATED 후 DELETED가 같은 배치에 오면") {
                service.applyAll(
                    listOf(
                        event(7L, ProductChangeType.CREATED),
                        event(7L, ProductChangeType.DELETED),
                    ),
                )

                Then("마지막 상태(DELETED)만 반영돼 삭제된다") {
                    verify { port.deleteAllById(listOf(7L)) }
                    verify(exactly = 0) { port.saveAll(any()) }
                }
            }
        }

        Given("빈 배치") {
            val port = mockk<ProductIndexPort>(relaxed = true)
            val service = newService(port)

            When("applyAll(emptyList())") {
                service.applyAll(emptyList())

                Then("아무 것도 하지 않는다") {
                    verify(exactly = 0) { port.saveAll(any()) }
                    verify(exactly = 0) { port.deleteAllById(any()) }
                }
            }
        }

        Given("계약 위반(CREATED인데 name 누락)") {
            val port = mockk<ProductIndexPort>(relaxed = true)
            val service = newService(port)

            When("applyAll을 호출하면") {
                Then("즉시 실패한다(프로듀서 계약 위반)") {
                    shouldThrow<IllegalArgumentException> {
                        service.applyAll(listOf(event(9L, ProductChangeType.CREATED, name = null)))
                    }
                }
            }
        }
    })
