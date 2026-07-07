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
            ),
        )

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<Any, Any>): KafkaTemplate<Any, Any> =
        KafkaTemplate(producerFactory)
}
