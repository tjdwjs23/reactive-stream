package demo.search.application.service

import demo.search.application.port.`in`.ProductRelayOutboxUseCase
import demo.search.application.port.`in`.RelayResult
import demo.search.application.port.out.EventPublisherPort
import demo.search.application.port.out.ProductObservabilityPort
import demo.search.application.port.out.ProductOutboxRelayPort
import demo.search.events.ProductChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// 상품 아웃박스 릴레이. RelayOutboxService(board)와 같은 순서 보존 로직을 product_outbox → product-changed로 적용합니다.
// board 릴레이와 물리적으로 분리해(별도 포트·토픽·백로그 게이지) 독립 운영합니다 — board 경로는 무변경.
// 한 건이라도 발행 실패하면 그 지점에서 멈춰(순서 보존) 다음 사이클에서 재시도합니다. EventPublisherPort는 토픽 무관이라 공유합니다.
@Service
class ProductRelayOutboxService(
    private val productOutboxRelayPort: ProductOutboxRelayPort,
    private val eventPublisherPort: EventPublisherPort,
    private val observability: ProductObservabilityPort,
    @Value("\${search.outbox.relay.batch-size:100}") private val batchSize: Int = 100,
) : ProductRelayOutboxUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun relay(): RelayResult {
        val batch = productOutboxRelayPort.readUnpublished(batchSize)
        if (batch.isEmpty()) {
            observability.updateOutboxBacklog(0)
            return RelayResult(0)
        }

        val publishedIds = ArrayList<Long>(batch.size)
        for (record in batch) {
            try {
                eventPublisherPort.publish(ProductChangedEvent.TOPIC, record.partitionKey, record.payload)
                publishedIds.add(record.id)
            } catch (e: Exception) {
                log.warn(
                    "product outbox relay stopped at id={} (published {} so far); retry next cycle. cause={}",
                    record.id,
                    publishedIds.size,
                    e.toString(),
                )
                break
            }
        }

        if (publishedIds.isNotEmpty()) productOutboxRelayPort.markPublished(publishedIds)
        reportBacklog()
        return RelayResult(publishedIds.size)
    }

    private fun reportBacklog() {
        try {
            observability.updateOutboxBacklog(productOutboxRelayPort.countUnpublished())
        } catch (e: Exception) {
            log.warn("failed to report product outbox backlog; skipping this cycle. cause={}", e.toString())
        }
    }
}
