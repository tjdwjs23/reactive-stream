package demo.search.adapter.out.observability

import demo.search.application.port.out.ObservabilityPort
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

// ObservabilityPort의 Micrometer 구현. 비즈니스 사건을 메트릭으로 변환하는 "유일한" 지점입니다.
// (여기서만 Micrometer를 알고, 서비스/도메인은 포트 인터페이스만 봅니다 — 헥사고날 의존성 방향 준수.)
//
// 카운터/서머리는 생성 시 한 번 등록해 재사용합니다. MeterRegistry는 Boot가 자동 구성한 빈으로,
// Micrometer OTLP 레지스트리가 물려 있어 메트릭이 OTLP로 Alloy→Mimir에 push됩니다(board_create_total 등).
@Component
class MicrometerObservabilityAdapter(
    registry: MeterRegistry,
) : ObservabilityPort {
    // 메트릭 이름은 present-tense 동사로 통일합니다. OpenMetrics는 _total/_created/_count/_sum/_bucket 등을
    // 예약 접미사로 취급하므로, 과거분사형("board.created")을 쓰면 Prometheus 렌더링 시 접미사가 잘려
    // board_total 처럼 뭉개집니다. 그래서 create/update/delete/view/search/flush/archive 로 둡니다.
    private val created = Counter.builder("board.create").description("생성된 게시글 수").register(registry)
    private val updated = Counter.builder("board.update").description("수정된 게시글 수").register(registry)
    private val deleted = Counter.builder("board.delete").description("삭제된 게시글 수").register(registry)
    private val viewed = Counter.builder("board.view").description("게시글 단건 조회 수").register(registry)
    private val searched = Counter.builder("board.search").description("검색 수행 횟수").register(registry)

    // 검색 1회당 적중 건수의 분포(합/개수/최대). 평균 적중 수, 무적중 비율 등을 관측할 수 있습니다.
    private val searchHits =
        DistributionSummary
            .builder("board.search.hits")
            .description("검색 1회당 적중 건수")
            .register(registry)

    private val viewCountsFlushed =
        Counter.builder("board.view-count.flush").description("플러시로 DB에 반영된 게시글 수").register(registry)
    private val archived = Counter.builder("board.archive").description("아카이브 배치로 삭제된 게시글 수").register(registry)

    // 아웃박스 미발행 백로그(현재 상태 → 게이지). AtomicLong을 백킹 소스로 등록하고, 릴레이 사이클마다 갱신합니다.
    // 릴레이가 죽거나 발행이 밀리면 커지고, 따라잡으면 0으로 수렴 — 이벤트 파이프라인 건강의 핵심 SLI입니다.
    private val outboxBacklog = AtomicLong(0)

    init {
        Gauge
            .builder("board.outbox.unpublished", outboxBacklog) { it.get().toDouble() }
            .description("아웃박스 미발행(백로그) 이벤트 수")
            .register(registry)
    }

    override fun boardCreated() = created.increment()

    override fun boardUpdated() = updated.increment()

    override fun boardDeleted() = deleted.increment()

    override fun boardViewed() = viewed.increment()

    override fun boardSearched(hitCount: Int) {
        searched.increment()
        searchHits.record(hitCount.toDouble())
    }

    override fun viewCountsFlushed(boardCount: Int) = viewCountsFlushed.increment(boardCount.toDouble())

    override fun boardsArchived(count: Int) = archived.increment(count.toDouble())

    override fun updateOutboxBacklog(count: Long) {
        outboxBacklog.set(count)
    }
}
