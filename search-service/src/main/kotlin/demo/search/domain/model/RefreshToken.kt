package demo.search.domain.model

import java.time.LocalDateTime

// 리프레시 토큰(순수 Kotlin 도메인 모델). Board/User와 동일하게 프레임워크/영속성 애노테이션이 새어 들어가지 않습니다.
// 원문 토큰은 클라이언트에게만 주어지고 서버는 해시(tokenHash)만 보관합니다 — 이 모델도 해시만 가집니다.
// revokedAt가 채워지면 폐기된 토큰이며(회전 또는 재사용 감지), 이후 제시되면 재사용으로 간주합니다.
data class RefreshToken(
    val id: Long? = null,
    val userId: Long,
    val tokenHash: String,
    val expiresAt: LocalDateTime,
    val revokedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
) {
    // now가 만료 시각 이상이면 만료(경계 포함 — 만료 시각 정각은 만료로 본다).
    // 재발급(AuthService.refresh)은 "폐기됨(재사용 감지)"과 "만료됨"을 서로 다르게 처리해야 하므로
    // isActive 같은 합성 판정 대신 revokedAt/isExpired를 개별로 검사합니다.
    fun isExpired(now: LocalDateTime): Boolean = !now.isBefore(expiresAt)
}
