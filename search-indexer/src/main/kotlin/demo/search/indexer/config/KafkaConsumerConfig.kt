package demo.search.indexer.config

import demo.search.indexer.application.port.out.IndexerObservabilityPort
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.MicrometerConsumerListener
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

// Kafka 컨슈머를 명시적으로 구성합니다.
//
// Spring Boot 4는 자동 구성을 기술별 모듈로 분리해, 라이브러리(spring-kafka)만으로는 컨슈머 팩토리/리스너 컨테이너와
// @KafkaListener 활성화(@EnableKafka)가 딸려오지 않습니다. 그래서 여기서 직접 구성합니다.
//
// key/value 모두 String(직렬화된 이벤트 원문 payload). 신규 그룹은 earliest로 토픽 처음부터 소비(초기 백필),
// 이후엔 커밋된 오프셋을 따라갑니다.
//
// 이 구성이 담당하는 세 가지:
//  1) 배치 소비 + 병렬성(isBatchListener, concurrency) — 벌크 색인으로 처리량 확보.
//  2) DLQ(board-changed-dlq) — 재시도해도 실패하는 메시지를 격리해 조용한 유실/무한 재시도 정체를 막음.
//  3) 컨슈머 메트릭(MicrometerConsumerListener) — kafka.consumer.* (records-lag 포함)를 OTLP로 export.
@Configuration
@EnableKafka
class KafkaConsumerConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    @Value("\${spring.kafka.consumer.group-id}") private val groupId: String,
    @Value("\${spring.kafka.listener.concurrency:3}") private val concurrency: Int,
) {
    @Bean
    fun consumerFactory(meterRegistry: MeterRegistry): ConsumerFactory<String, String> =
        DefaultKafkaConsumerFactory<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).apply {
            // 컨슈머 랙 등 kafka.consumer.* 메트릭을 Micrometer에 바인딩 → OTLP export(Grafana에서 records-lag 관찰).
            // 팩토리를 직접 만들면 Boot 자동 바인딩이 안 걸리므로 여기서 리스너를 등록합니다.
            addListener(MicrometerConsumerListener(meterRegistry))
        }

    // DLQ 발행용 프로듀서. 격리된 메시지도 유실되면 안 되므로 acks=all + 멱등.
    @Bean
    fun dlqProducerFactory(): ProducerFactory<String, String> =
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
    fun dlqKafkaTemplate(dlqProducerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(dlqProducerFactory)

    // 재시도해도 실패하는 레코드를 board-changed-dlq로 보내는 공통 에러 핸들러.
    //  - 역직렬화 실패(포이즌): 리스너가 BatchListenerFailedException(index)를 던져 "그 레코드만" DLQ, 나머지는 계속.
    //  - ES 색인 실패(일시적): 배치를 2초 간격 3회(총 4회) 재시도한 뒤에도 실패하면 DLQ로 격리.
    // DLQ 파티션은 브로커가 키 기반으로 선택하도록 -1로 둡니다(원본 토픽과 파티션 수가 달라도 안전).
    //
    // 목적지 리졸버 람다는 "실제로 DLQ로 발행되는" 레코드마다 한 번 호출되므로, 여기서 격리 카운터를 증가시킵니다
    // (board_indexer_dlq_total). DLQ 격리는 조용한 유실은 아니지만 사람이 조사해야 할 신호라 반드시 메트릭으로 남깁니다.
    @Bean
    fun kafkaErrorHandler(
        dlqKafkaTemplate: KafkaTemplate<String, String>,
        observability: IndexerObservabilityPort,
    ): DefaultErrorHandler {
        val recoverer =
            DeadLetterPublishingRecoverer(dlqKafkaTemplate) { _, _ ->
                observability.messageDeadLettered()
                TopicPartition(DLQ_TOPIC, -1)
            }
        return DefaultErrorHandler(recoverer, FixedBackOff(2000L, 3L))
    }

    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        kafkaErrorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            // 배치 소비: 한 poll의 여러 레코드를 리스너에 List로 넘겨 벌크 색인(saveAll)합니다.
            setBatchListener(true)
            // 토픽 파티션 수만큼 병렬 소비(파티션이 N개면 최대 N개 스레드). 처리량 확보.
            setConcurrency(concurrency)
            setCommonErrorHandler(kafkaErrorHandler)
            // 분산 트레이싱: 메시지 헤더의 traceparent를 추출해 search-service의 발행 span과 연결합니다
            // (@KafkaListener observation). 이게 꺼져 있으면 두 서비스의 트레이스가 끊긴 채 따로 보입니다.
            containerProperties.isObservationEnabled = true
        }

    companion object {
        // 처리 불가(포이즌) 메시지가 무한 재시도로 파이프라인을 막지 않도록 격리하는 DLQ 토픽.
        const val DLQ_TOPIC = "board-changed-dlq"
    }
}
