package demo.board.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

// Kafka 프로듀서를 명시적으로 구성합니다.
//
// Spring Boot 4는 자동 구성을 기술별 모듈로 분리했고, 라이브러리(org.springframework.kafka:spring-kafka)만으로는
// Boot의 Kafka 자동 구성이 딸려오지 않아 ProducerFactory/KafkaTemplate 빈이 만들어지지 않습니다. 그래서 아웃박스
// 릴레이가 쓰는 프로듀서를 여기서 직접 정의합니다 — 직렬화/acks/멱등 설정이 한곳에 드러나 자동 구성보다 오히려 명료합니다.
//
// key/value 모두 String(직렬화된 이벤트 원문 payload). acks=all + 멱등 프로듀서로 발행 유실/중복을 줄입니다
// (소비자는 event_id로 최종 멱등 처리). 빈 타입은 KafkaEventPublisherAdapter가 주입받는 <Any, Any>에 맞춥니다.
@Configuration
class KafkaProducerConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
) {
    @Bean
    fun boardEventProducerFactory(): ProducerFactory<Any, Any> =
        DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                // 무한 대기 차단(회복탄력성): 브로커가 죽거나 느려도 send가 매달리지 않도록 상한을 둡니다.
                //  - MAX_BLOCK_MS: 메타데이터 대기/버퍼 확보에 send가 블록될 최대 시간.
                //  - REQUEST_TIMEOUT_MS: 브로커 응답 1회 대기 상한.
                //  - DELIVERY_TIMEOUT_MS: 재시도 포함 전체 발행 완료 상한(≥ request timeout). 초과 시 실패로 전파돼
                //    릴레이가 그 지점에서 멈추고 다음 사이클에 재시도합니다(순서 보존·유실 없음). 어댑터의 서킷브레이커가 반복 실패를 감지.
                ProducerConfig.MAX_BLOCK_MS_CONFIG to 5000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 10000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 30000,
            ),
        )

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<Any, Any>): KafkaTemplate<Any, Any> =
        KafkaTemplate(producerFactory).apply {
            // 분산 트레이싱: 발행 시 traceparent를 Kafka 메시지 헤더에 주입해, board-service의 트레이스가
            // search-indexer 소비 span과 하나로 이어지게 합니다(Micrometer/OTel KafkaTemplate observation).
            setObservationEnabled(true)
        }
}
