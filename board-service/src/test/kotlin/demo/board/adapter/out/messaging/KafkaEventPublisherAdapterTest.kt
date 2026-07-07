package demo.board.adapter.out.messaging

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class KafkaEventPublisherAdapterTest :
    BehaviorSpec({

        Given("Kafka 발행 어댑터") {
            val kafkaTemplate = mockk<KafkaTemplate<Any, Any>>()
            val registry = CircuitBreakerRegistry.ofDefaults()
            val adapter = KafkaEventPublisherAdapter(kafkaTemplate, registry)
            val completed = CompletableFuture.completedFuture(mockk<SendResult<Any, Any>>())
            every { kafkaTemplate.send("board-changed", "7", "payload-7") } returns completed

            When("publish를 호출하면") {
                adapter.publish("board-changed", "7", "payload-7")

                Then("KafkaTemplate.send(topic, key, payload)로 위임하고 완료를 기다린다") {
                    verify { kafkaTemplate.send("board-changed", "7", "payload-7") }
                }
            }
        }

        Given("서킷브레이커가 열려 있을 때") {
            val kafkaTemplate = mockk<KafkaTemplate<Any, Any>>()
            val registry = CircuitBreakerRegistry.ofDefaults()
            val adapter = KafkaEventPublisherAdapter(kafkaTemplate, registry)
            registry.circuitBreaker("kafka-publisher").transitionToOpenState()

            When("publish를 호출하면") {
                Then("브로커를 호출하지 않고 즉시 실패한다(CallNotPermittedException)") {
                    shouldThrow<CallNotPermittedException> {
                        adapter.publish("board-changed", "7", "payload-7")
                    }
                    verify(exactly = 0) { kafkaTemplate.send(any(), any<Any>(), any<Any>()) }
                }
            }
        }
    })
