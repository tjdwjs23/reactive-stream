package demo.search.support

import demo.search.application.port.out.ProductObservabilityPort

// 서비스 단위 테스트용 no-op ProductObservabilityPort. 메트릭 기록은 베스트에포트 부수효과라
// 비즈니스 로직 테스트에서는 아무 것도 하지 않습니다(메트릭 정확성은 MicrometerProductObservabilityAdapterTest가 검증).
object NoOpProductObservabilityPort : ProductObservabilityPort {
    override fun productCreated() = Unit

    override fun productDeleted() = Unit

    override fun productSearched(hitCount: Int) = Unit

    override fun productAutocompleted(hitCount: Int) = Unit

    override fun updateOutboxBacklog(count: Long) = Unit
}
