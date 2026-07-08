package demo.search.application.service

import demo.search.application.port.out.EventPublisherPort
import demo.search.application.port.out.OutboxRecord
import demo.search.application.port.out.ProductObservabilityPort
import demo.search.application.port.out.ProductOutboxRelayPort
import demo.search.events.ProductChangedEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder

// RelayOutboxServiceTest(board)와 대칭. 순서 보존/실패 중단/백로그 보고를 product_outbox → product-changed로 검증.
class ProductRelayOutboxServiceTest :
    BehaviorSpec({

        fun record(id: Long) = OutboxRecord(id = id, partitionKey = id.toString(), payload = "payload-$id")

        Given("미발행 이벤트가 없을 때") {
            val relayPort = mockk<ProductOutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ProductObservabilityPort>(relaxed = true)
            val service = ProductRelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns emptyList()

            When("relay를 호출하면") {
                val result = service.relay()

                Then("아무것도 발행하지 않고 백로그를 0으로 보고한다") {
                    result.published shouldBe 0
                    verify(exactly = 0) { publisher.publish(any(), any(), any()) }
                    verify { observability.updateOutboxBacklog(0) }
                }
            }
        }

        Given("전부 성공할 때") {
            val relayPort = mockk<ProductOutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ProductObservabilityPort>(relaxed = true)
            val service = ProductRelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns listOf(record(1), record(2), record(3))
            every { publisher.publish(any(), any(), any()) } just Runs
            every { relayPort.markPublished(any()) } just Runs
            every { relayPort.countUnpublished() } returns 0L

            When("relay를 호출하면") {
                val result = service.relay()

                Then("product-changed로 기록 순서대로 발행하고 전부 완료 표시한다") {
                    result.published shouldBe 3
                    verifyOrder {
                        publisher.publish(ProductChangedEvent.TOPIC, "1", "payload-1")
                        publisher.publish(ProductChangedEvent.TOPIC, "2", "payload-2")
                        publisher.publish(ProductChangedEvent.TOPIC, "3", "payload-3")
                    }
                    verify { relayPort.markPublished(listOf(1L, 2L, 3L)) }
                }
            }
        }

        Given("중간에 발행이 실패할 때") {
            val relayPort = mockk<ProductOutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ProductObservabilityPort>(relaxed = true)
            val service = ProductRelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns listOf(record(1), record(2), record(3))
            every { publisher.publish(ProductChangedEvent.TOPIC, "1", "payload-1") } just Runs
            every { publisher.publish(ProductChangedEvent.TOPIC, "2", "payload-2") } throws
                RuntimeException("broker down")
            every { relayPort.markPublished(any()) } just Runs
            every { relayPort.countUnpublished() } returns 2L

            When("relay를 호출하면") {
                val result = service.relay()

                Then("실패 지점에서 멈추고 성공분(id=1)만 완료 표시하며 남은 백로그(2)를 보고한다") {
                    result.published shouldBe 1
                    verify(exactly = 0) { publisher.publish(ProductChangedEvent.TOPIC, "3", "payload-3") }
                    verify { relayPort.markPublished(listOf(1L)) }
                    verify { observability.updateOutboxBacklog(2) }
                }
            }
        }
    })
