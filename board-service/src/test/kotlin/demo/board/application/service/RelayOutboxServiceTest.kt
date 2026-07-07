package demo.board.application.service

import demo.board.application.port.out.EventPublisherPort
import demo.board.application.port.out.OutboxRecord
import demo.board.application.port.out.OutboxRelayPort
import demo.board.events.BoardChangedEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk

class RelayOutboxServiceTest :
    BehaviorSpec({

        fun record(id: Long) = OutboxRecord(id = id, partitionKey = id.toString(), payload = "payload-$id")

        Given("미발행 이벤트가 없을 때") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val service = RelayOutboxService(relayPort, publisher, batchSize = 100)
            coEvery { relayPort.readUnpublished(100) } returns emptyList()

            When("relay를 호출하면") {
                val result = service.relay()

                Then("아무것도 발행하지 않고 markPublished도 부르지 않는다") {
                    result.published shouldBe 0
                    coVerify(exactly = 0) { publisher.publish(any(), any(), any()) }
                    coVerify(exactly = 0) { relayPort.markPublished(any()) }
                }
            }
        }

        Given("미발행 이벤트가 여러 건이고 발행이 모두 성공할 때") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val service = RelayOutboxService(relayPort, publisher, batchSize = 100)
            coEvery { relayPort.readUnpublished(100) } returns listOf(record(1), record(2), record(3))
            coEvery { publisher.publish(any(), any(), any()) } just Runs
            coEvery { relayPort.markPublished(any()) } just Runs

            When("relay를 호출하면") {
                val result = service.relay()

                Then("기록 순서대로 발행하고 전부 발행 완료로 표시한다") {
                    result.published shouldBe 3
                    coVerifyOrder {
                        publisher.publish(BoardChangedEvent.TOPIC, "1", "payload-1")
                        publisher.publish(BoardChangedEvent.TOPIC, "2", "payload-2")
                        publisher.publish(BoardChangedEvent.TOPIC, "3", "payload-3")
                    }
                    coVerify { relayPort.markPublished(listOf(1L, 2L, 3L)) }
                }
            }
        }

        Given("중간에 발행이 실패할 때") {
            val relayPort = mockk<OutboxRelayPort>()
            val publisher = mockk<EventPublisherPort>()
            val service = RelayOutboxService(relayPort, publisher, batchSize = 100)
            coEvery { relayPort.readUnpublished(100) } returns listOf(record(1), record(2), record(3))
            coEvery { publisher.publish(BoardChangedEvent.TOPIC, "1", "payload-1") } just Runs
            coEvery {
                publisher.publish(BoardChangedEvent.TOPIC, "2", "payload-2")
            } throws RuntimeException("broker down")
            coEvery { relayPort.markPublished(any()) } just Runs

            When("relay를 호출하면") {
                val result = service.relay()

                Then("실패 지점에서 멈추고 성공분(id=1)만 발행 완료로 표시한다 — 뒤 이벤트(id=3)는 건드리지 않는다") {
                    result.published shouldBe 1
                    coVerify(exactly = 0) { publisher.publish(BoardChangedEvent.TOPIC, "3", "payload-3") }
                    coVerify { relayPort.markPublished(listOf(1L)) }
                }
            }
        }
    })
