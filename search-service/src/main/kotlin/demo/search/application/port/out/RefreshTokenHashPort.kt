package demo.search.application.port.out

// 리프레시 토큰의 "불투명 원문 생성"과 "해시"를 담당하는 out-port. 구체 알고리즘(SecureRandom/SHA-256)은 어댑터가 감춥니다.
//
// 원문은 클라이언트에게만 주어지고, 서버는 hash(원문)만 저장/조회합니다 — DB가 유출돼도 원문을 복원할 수 없습니다.
// (access JWT와 달리 리프레시 토큰은 서명 검증이 아니라 저장소 대조로 검증하므로, 저장값을 해시로 둡니다.)
interface RefreshTokenHashPort {
    // 추측 불가능한 불투명 랜덤 토큰(클라이언트 반환용).
    fun generateToken(): String

    // 원문 토큰의 해시(저장/조회 키). 같은 입력은 항상 같은 해시(결정적).
    fun hash(rawToken: String): String
}
