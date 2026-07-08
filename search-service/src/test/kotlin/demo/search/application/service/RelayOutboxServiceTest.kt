package demo.search.application.service

import demo.search.application.port.out.EventPublisherPort
import demo.search.application.port.out.ObservabilityPort
import demo.search.application.port.out.OutboxRecord
import demo.search.application.port.out.OutboxRelayPort
import demo.search.events.BoardChangedEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder

class RelayOutboxServiceTest :
    BehaviorSpec({

        fun record(id: Long) = OutboxRecord(id = id, partitionKey = id.toString(), payload = "payload-$id")

        Given("미발행 이벤트가 없을 때") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ObservabilityPort>(relaxed = true)
            val service = RelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns emptyList()

            When("relay를 호출하면") {
                val result = service.relay()

                Then("아무것도 발행하지 않고 markPublished도 부르지 않으며 백로그를 0으로 보고한다") {
                    result.published shouldBe 0
                    verify(exactly = 0) { publisher.publish(any(), any(), any()) }
                    verify(exactly = 0) { relayPort.markPublished(any()) }
                    verify { observability.updateOutboxBacklog(0) }
                }
            }
        }

        Given("미발행 이벤트가 여러 건이고 발행이 모두 성공할 때") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ObservabilityPort>(relaxed = true)
            val service = RelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns listOf(record(1), record(2), record(3))
            every { publisher.publish(any(), any(), any()) } just Runs
            every { relayPort.markPublished(any()) } just Runs
            every { relayPort.countUnpublished() } returns 0L

            When("relay를 호출하면") {
                val result = service.relay()

                Then("기록 순서대로 발행하고 전부 발행 완료로 표시하며 잔여 백로그(0)를 게이지에 보고한다") {
                    result.published shouldBe 3
                    verifyOrder {
                        publisher.publish(BoardChangedEvent.TOPIC, "1", "payload-1")
                        publisher.publish(BoardChangedEvent.TOPIC, "2", "payload-2")
                        publisher.publish(BoardChangedEvent.TOPIC, "3", "payload-3")
                    }
                    verify { relayPort.markPublished(listOf(1L, 2L, 3L)) }
                    verify { observability.updateOutboxBacklog(0) }
                }
            }
        }

        Given("중간에 발행이 실패할 때") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ObservabilityPort>(relaxed = true)
            val service = RelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns listOf(record(1), record(2), record(3))
            every { publisher.publish(BoardChangedEvent.TOPIC, "1", "payload-1") } just Runs
            every {
                publisher.publish(BoardChangedEvent.TOPIC, "2", "payload-2")
            } throws RuntimeException("broker down")
            every { relayPort.markPublished(any()) } just Runs
            // 발행이 밀려 아직 2건이 미발행으로 남아 있는 상태를 게이지가 반영해야 한다.
            every { relayPort.countUnpublished() } returns 2L

            When("relay를 호출하면") {
                val result = service.relay()

                Then("실패 지점에서 멈추고 성공분(id=1)만 발행 완료로 표시하며 남은 백로그(2)를 보고한다") {
                    result.published shouldBe 1
                    verify(exactly = 0) { publisher.publish(BoardChangedEvent.TOPIC, "3", "payload-3") }
                    verify { relayPort.markPublished(listOf(1L)) }
                    verify { observability.updateOutboxBacklog(2) }
                }
            }
        }

        Given("백로그 조회가 실패해도") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val observability = mockk<ObservabilityPort>(relaxed = true)
            val service = RelayOutboxService(relayPort, publisher, observability, batchSize = 100)
            every { relayPort.readUnpublished(100) } returns listOf(record(1))
            every { publisher.publish(any(), any(), any()) } just Runs
            every { relayPort.markPublished(any()) } just Runs
            every { relayPort.countUnpublished() } throws RuntimeException("db down")
            every { observability.updateOutboxBacklog(any()) } just Runs

            When("relay를 호출하면") {
                val result = service.relay()

                Then("발행 결과는 정상 반환되고 게이지 오류는 삼켜진다(메트릭은 베스트에포트)") {
                    result.published shouldBe 1
                    verify { relayPort.markPublished(listOf(1L)) }
                }
            }
        }
    })
