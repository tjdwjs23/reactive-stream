package demo.search.indexer.adapter.out.observability

import demo.search.indexer.application.port.out.IndexerObservabilityPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

// IndexerObservabilityPort의 Micrometer 구현. 색인 파이프라인 사건을 메트릭으로 변환하는 "유일한" 지점입니다
// (여기서만 Micrometer를 알고, 서비스/설정은 포트 인터페이스만 봅니다 — search-service와 동일한 헥사고날 규칙).
//
// MeterRegistry는 Boot가 자동 구성한 빈으로, micrometer-registry-otlp가 물려 있어 메트릭이 OTLP로 Alloy→Grafana Cloud에
// push됩니다. 메트릭 이름은 search-service 관례대로 present-tense 동사로 두어 OpenMetrics 예약 접미사(_total/_bucket
// 등)와 충돌하지 않게 합니다(board_indexer_indexed_total, board_indexer_dlq_total, board_indexer_batch_milliseconds).
@Component
class MicrometerIndexerObservabilityAdapter(
    registry: MeterRegistry,
) : IndexerObservabilityPort {
    private val indexed =
        Counter.builder("board.indexer.indexed").description("색인(upsert)에 반영된 문서 수").register(registry)
    private val deleted =
        Counter.builder("board.indexer.deleted").description("색인에서 삭제된 문서 수").register(registry)

    // DLQ로 격리된 메시지 수. 정상 상태에서는 0으로 평평해야 하며, 증가는 포이즌/영속 실패의 직접 신호입니다.
    private val deadLettered =
        Counter.builder("board.indexer.dlq").description("board-changed-dlq로 격리된 메시지 수").register(registry)

    // 배치 한 번의 ES 쓰기(upsert+delete) 소요 시간. publishPercentileHistogram으로 _bucket을 내보내
    // Grafana에서 p95/p99 색인 지연을 볼 수 있게 합니다(http_server_requests와 동일한 관측 방식).
    private val batchTimer =
        Timer
            .builder("board.indexer.batch")
            .description("색인 배치(ES 벌크 쓰기) 소요 시간")
            .publishPercentileHistogram()
            .register(registry)

    override fun recordIndexingBatch(block: () -> Unit) = batchTimer.record(Runnable { block() })

    override fun boardsIndexed(count: Int) = indexed.increment(count.toDouble())

    override fun boardsDeleted(count: Int) = deleted.increment(count.toDouble())

    override fun messageDeadLettered() = deadLettered.increment()
}
