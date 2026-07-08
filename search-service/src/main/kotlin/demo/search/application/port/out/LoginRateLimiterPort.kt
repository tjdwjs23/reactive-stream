package demo.search.application.port.out

// 로그인 brute-force 방어용 rate limiter out-port. 저장 매체(Redis)는 어댑터가 감춥니다.
// key는 보통 username입니다(사용자 계정 단위로 실패 횟수를 셈).
interface LoginRateLimiterPort {
    // 현재 key가 차단 상태인지(윈도우 내 실패가 임계치 이상).
    fun isBlocked(key: String): Boolean

    // 로그인 실패 1건 기록(윈도우 카운터 증가). 최초 실패 시 윈도우 TTL을 건다.
    fun recordFailure(key: String)

    // 로그인 성공 시 카운터 초기화(정상 사용자가 이전 실패로 잠기지 않도록).
    fun reset(key: String)
}
