package demo.board.indexer.config

import demo.board.indexer.application.port.out.ProductIndexerObservabilityPort
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

// 상품 컨슈머 전용 설정(KafkaConsumerConfig[board]와 병렬). @EnableKafka와 consumerFactory/dlqKafkaTemplate 빈은
// board의 KafkaConsumerConfig가 이미 제공하므로 재사용하고, 여기서는 product 전용 에러 핸들러(→ product-changed-dlq)와
// 리스너 컨테이너 팩토리만 추가합니다. ProductChangedListener가 이 팩토리를 명시적으로 지정해 씁니다.
@Configuration
class ProductKafkaConsumerConfig(
    @Value("\${spring.kafka.listener.concurrency:3}") private val concurrency: Int,
) {
    // 재시도해도 실패하는 상품 레코드를 product-changed-dlq로 격리. 발행마다 product DLQ 카운터 증가.
    @Bean
    fun productKafkaErrorHandler(
        dlqKafkaTemplate: KafkaTemplate<String, String>,
        observability: ProductIndexerObservabilityPort,
    ): DefaultErrorHandler {
        val recoverer =
            DeadLetterPublishingRecoverer(dlqKafkaTemplate) { _, _ ->
                observability.messageDeadLettered()
                TopicPartition(DLQ_TOPIC, -1)
            }
        return DefaultErrorHandler(recoverer, FixedBackOff(2000L, 3L))
    }

    @Bean
    fun productKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>,
        productKafkaErrorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory)
            setBatchListener(true)
            setConcurrency(concurrency)
            setCommonErrorHandler(productKafkaErrorHandler)
            containerProperties.isObservationEnabled = true
        }

    companion object {
        const val DLQ_TOPIC = "product-changed-dlq"
    }
}
