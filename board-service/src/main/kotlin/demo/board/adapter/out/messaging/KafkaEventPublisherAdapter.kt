package demo.board.adapter.out.messaging

import demo.board.application.port.out.EventPublisherPort
import demo.board.config.ResilienceConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

// EventPublisherPort의 Kafka 구현. MVC/블로킹 스택이라 KafkaTemplate.send가 돌려주는 CompletableFuture를
// get()으로 대기해 발행 ack까지 확인합니다(가상 스레드 위에서 블로킹). 발행 실패(브로커 다운 등)는 예외로
// 전파되고, 릴레이 서비스가 그 지점에서 멈춰 다음 사이클에 재시도합니다(아웃박스 행은 미발행으로 남아 유실 없음).
//
// Boot가 자동 구성하는 KafkaTemplate 빈 타입은 KafkaTemplate<Object, Object>라, <String, String>으로 주입받으면
// 제네릭이 맞지 않아 실패합니다. 그래서 자동 구성 빈 타입(<Any, Any>)으로 주입받고, 실제 문자열 직렬화는
// application.yml/KafkaProducerConfig의 StringSerializer가 보장합니다(우리는 항상 String key/payload만 넘김).
@Component
class KafkaEventPublisherAdapter(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : EventPublisherPort {
    // 브로커가 반복 실패/지연하면 서킷이 열려 즉시 실패합니다. 릴레이(RelayOutboxService)는 그 지점에서 멈추고
    // 다음 사이클에 재시도하므로(순서 보존·미발행 행 잔존 → 유실 없음), 브레이커가 매 레코드 타임아웃 낭비를 막습니다.
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.KAFKA_PUBLISHER)

    override fun publish(
        topic: String,
        key: String,
        payload: String,
    ) {
        breaker.executeRunnable {
            // get()으로 발행 완료(ack)까지 블로킹 — 실패 시 예외가 나 릴레이가 이 지점에서 멈춥니다.
            kafkaTemplate.send(topic, key, payload).get()
        }
    }
}
