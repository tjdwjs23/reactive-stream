package demo.board.adapter.out.messaging

import demo.board.application.port.out.EventPublisherPort
import kotlinx.coroutines.future.await
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

// EventPublisherPort의 Kafka 구현. KafkaTemplate.send는 CompletableFuture를 돌려주므로 await로 코루틴에 잇습니다.
// 발행 실패(브로커 다운 등)는 예외로 전파되고, 릴레이 서비스가 그 지점에서 멈춰 다음 사이클에 재시도합니다
// (아웃박스 행은 미발행으로 남으므로 유실이 없습니다).
//
// Boot가 자동 구성하는 KafkaTemplate 빈의 타입은 KafkaTemplate<Object, Object>라, <String, String>으로 주입받으면
// 제네릭이 맞지 않아 실패합니다. 그래서 자동 구성 빈 타입(<Any, Any>)으로 주입받고, 실제 문자열 직렬화는
// application.yml의 key/value StringSerializer가 보장합니다(우리는 항상 String key/payload만 넘김).
@Component
class KafkaEventPublisherAdapter(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
) : EventPublisherPort {
    override suspend fun publish(
        topic: String,
        key: String,
        payload: String,
    ) {
        kafkaTemplate.send(topic, key, payload).await()
    }
}
