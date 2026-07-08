package demo.board.application.port.`in`

// 아웃박스에 쌓인 미발행 이벤트를 브로커로 밀어내는(relay) 유스케이스.
// @Scheduled 트리거(주기 폴링) 또는 운영 트리거가 호출합니다.
interface RelayOutboxUseCase {
    fun relay(): RelayResult
}

// 이번 릴레이 사이클에서 실제로 발행한 건수. 관측/로그용.
data class RelayResult(
    val published: Int,
)
