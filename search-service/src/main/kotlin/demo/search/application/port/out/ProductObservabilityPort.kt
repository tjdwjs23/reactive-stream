package demo.search.application.port.out

// 상품 도메인 "비즈니스 사건"을 관측 백엔드에 기록하는 out-port(프레임워크 무의존).
// Board의 ObservabilityPort와 분리해, board 경로/테스트를 건드리지 않고 상품 메트릭을 독립적으로 냅니다
// (product.* 네임스페이스). 구체 기술(Micrometer)은 어댑터만 압니다.
interface ProductObservabilityPort {
    fun productCreated()

    fun productDeleted()

    // 검색 1회 + 적중 건수(분포).
    fun productSearched(hitCount: Int)

    // 자동완성 1회 + 제안 건수(분포).
    fun productAutocompleted(hitCount: Int)

    // 상품 아웃박스 미발행(백로그) 게이지. 릴레이 사이클마다 갱신.
    fun updateOutboxBacklog(count: Long)
}
