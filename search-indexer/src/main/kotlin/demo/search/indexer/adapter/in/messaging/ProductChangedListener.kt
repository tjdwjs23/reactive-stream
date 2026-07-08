package demo.search.indexer.adapter.`in`.messaging

import demo.search.events.ProductChangedEvent
import demo.search.indexer.application.port.`in`.ApplyProductChangeUseCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.listener.BatchListenerFailedException
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// product-changed 토픽 컨슈머(BoardChangedListener와 대칭). 배치 소비 + 포이즌 메시지 DLQ 격리.
// board 리스너와 다른 그룹 id(-product)와 다른 컨테이너 팩토리(productKafkaListenerContainerFactory)를 써서
// 두 토픽의 소비/오프셋/DLQ를 독립적으로 운영합니다.
@Component
class ProductChangedListener(
    private val objectMapper: ObjectMapper,
    private val applyProductChangeUseCase: ApplyProductChangeUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [ProductChangedEvent.TOPIC],
        groupId = "\${spring.kafka.consumer.group-id}-product",
        containerFactory = "productKafkaListenerContainerFactory",
    )
    fun onMessages(records: List<ConsumerRecord<String, String>>) {
        val events = ArrayList<ProductChangedEvent>(records.size)
        records.forEachIndexed { index, record ->
            val event =
                try {
                    objectMapper.readValue(record.value(), ProductChangedEvent::class.java)
                } catch (e: Exception) {
                    if (events.isNotEmpty()) applyProductChangeUseCase.applyAll(events)
                    log.error(
                        "product-changed 역직렬화 실패 → DLQ 격리 (partition={} offset={})",
                        record.partition(),
                        record.offset(),
                        e,
                    )
                    throw BatchListenerFailedException("product-changed 역직렬화 실패", e, index)
                }
            events.add(event)
        }

        applyProductChangeUseCase.applyAll(events)
        log.info("product-changed 배치 색인 완료: {}건", events.size)
    }
}
