package demo.search.adapter.out.observability

import demo.search.application.port.out.ProductObservabilityPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

// ProductObservabilityPort의 Micrometer 구현. product.* 네임스페이스로 상품 도메인 메트릭을 냅니다
// (board.*와 분리). 메트릭 이름은 present-tense 동사(OpenMetrics 예약 접미사 회피)로 통일합니다.
@Component
class MicrometerProductObservabilityAdapter(
    registry: MeterRegistry,
) : ProductObservabilityPort {
    private val created = Counter.builder("product.create").description("생성된 상품 수").register(registry)
    private val deleted = Counter.builder("product.delete").description("삭제된 상품 수").register(registry)
    private val searched = Counter.builder("product.search").description("상품 검색 수행 횟수").register(registry)
    private val autocompleted =
        Counter.builder("product.autocomplete").description("상품 자동완성 수행 횟수").register(registry)

    private val searchHits =
        DistributionSummary.builder("product.search.hits").description("검색 1회당 적중 건수").register(registry)
    private val autocompleteHits =
        DistributionSummary.builder("product.autocomplete.hits").description("자동완성 1회당 제안 건수").register(registry)

    private val outboxBacklog = AtomicLong(0)

    init {
        Gauge
            .builder("product.outbox.unpublished", outboxBacklog) { it.get().toDouble() }
            .description("상품 아웃박스 미발행(백로그) 이벤트 수")
            .register(registry)
    }

    override fun productCreated() = created.increment()

    override fun productDeleted() = deleted.increment()

    override fun productSearched(hitCount: Int) {
        searched.increment()
        searchHits.record(hitCount.toDouble())
    }

    override fun productAutocompleted(hitCount: Int) {
        autocompleted.increment()
        autocompleteHits.record(hitCount.toDouble())
    }

    override fun updateOutboxBacklog(count: Long) {
        outboxBacklog.set(count)
    }
}
