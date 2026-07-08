package demo.board.indexer.adapter.out.observability

import demo.board.indexer.application.port.out.ProductIndexerObservabilityPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

// ProductIndexerObservabilityPort의 Micrometer 구현(product.indexer.* 네임스페이스). board 인덱서 관측과 분리됩니다.
@Component
class MicrometerProductIndexerObservabilityAdapter(
    registry: MeterRegistry,
) : ProductIndexerObservabilityPort {
    private val indexed =
        Counter.builder("product.indexer.indexed").description("색인(upsert)에 반영된 상품 수").register(registry)
    private val deleted =
        Counter.builder("product.indexer.deleted").description("색인에서 삭제된 상품 수").register(registry)
    private val deadLettered =
        Counter.builder("product.indexer.dlq").description("product-changed-dlq로 격리된 메시지 수").register(registry)
    private val batchTimer =
        Timer
            .builder("product.indexer.batch")
            .description("상품 색인 배치(ES 벌크 쓰기) 소요 시간")
            .publishPercentileHistogram()
            .register(registry)

    override fun recordIndexingBatch(block: () -> Unit) = batchTimer.record(Runnable { block() })

    override fun productsIndexed(count: Int) = indexed.increment(count.toDouble())

    override fun productsDeleted(count: Int) = deleted.increment(count.toDouble())

    override fun messageDeadLettered() = deadLettered.increment()
}
