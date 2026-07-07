package demo.board.support

import demo.board.application.port.out.ObservabilityPort

// 서비스 단위 테스트용 no-op ObservabilityPort. 메트릭 기록은 베스트에포트 부수효과라
// 비즈니스 로직 테스트에서는 아무 것도 하지 않는 구현을 주입합니다.
// (메트릭 기록 자체의 정확성은 MicrometerObservabilityAdapterTest가 검증합니다.)
object NoOpObservabilityPort : ObservabilityPort {
    override fun boardCreated() = Unit

    override fun boardUpdated() = Unit

    override fun boardDeleted() = Unit

    override fun boardViewed() = Unit

    override fun boardSearched(hitCount: Int) = Unit

    override fun viewCountsFlushed(boardCount: Int) = Unit

    override fun boardsArchived(count: Int) = Unit
}
