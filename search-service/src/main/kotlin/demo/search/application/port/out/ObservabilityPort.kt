package demo.search.application.port.out

// 도메인 "비즈니스 사건"을 관측 백엔드에 기록하는 out-port입니다.
//
// 헥사고날 규칙을 지키기 위해 이 인터페이스는 프레임워크에 무의존합니다 — Micrometer/OTel 같은 구체 기술은
// 어댑터(MicrometerObservabilityAdapter)만 알고, 서비스는 "게시글이 생성됐다" 같은 비즈니스 어휘로만 호출합니다.
// (메트릭 이름·타입·태그 같은 표현은 전적으로 어댑터의 결정입니다.)
//
// 기술 지표(HTTP 처리량/지연, JVM)는 Micrometer/Actuator가 자동 수집하지만, "게시글이 초당 몇 개 생성되나,
// 검색 적중은 몇 건이나" 같은 비즈니스 의미의 지표는 도메인 흐름에서만 알 수 있어 이 포트로 노출합니다.
//
// 모든 메서드는 인메모리 카운터 증가(논블로킹, I/O 없음)라 suspend가 아닙니다.
// 기록은 베스트에포트 부수효과입니다 — 실패해도 비즈니스 로직을 막지 않도록 성공 경로 끝에서 호출합니다.
interface ObservabilityPort {
    fun boardCreated()

    fun boardUpdated()

    fun boardDeleted()

    // 게시글 단건 조회 1건.
    fun boardViewed()

    // 검색 1회 수행 + 그 검색의 적중 건수(분포로 관측).
    fun boardSearched(hitCount: Int)

    // 조회수 플러시로 DB에 반영된 게시글 수.
    fun viewCountsFlushed(boardCount: Int)

    // 아카이브 배치로 삭제된 게시글 수.
    fun boardsArchived(count: Int)

    // 아웃박스 미발행(백로그) 이벤트 총수의 현재값. 카운터가 아니라 "현재 상태"라 게이지로 노출합니다
    // (릴레이가 밀리면 커지고, 따라잡으면 0으로 수렴). 릴레이 사이클마다 최신값으로 갱신합니다.
    fun updateOutboxBacklog(count: Long)
}
