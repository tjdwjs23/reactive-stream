package demo.search.domain.model

import java.time.LocalDateTime

// 인증 사용자. Board와 마찬가지로 순수 Kotlin 도메인 모델입니다 —
// 프레임워크/영속성/보안 애노테이션이 새어 들어가지 않습니다(변환은 어댑터의 매퍼가 담당).
// passwordHash는 이미 인코딩된 값이며, 도메인은 원문 비밀번호를 알지 못합니다(인코딩/검증은 out-port).
data class User(
    val id: Long? = null,
    val username: String,
    val passwordHash: String,
    val role: Role = Role.USER,
    // 생성 시각도 Board와 동일하게 도메인이 벽시계를 직접 읽지 않고 생성 경계에서 주입받습니다.
    val createdAt: LocalDateTime,
) {
    companion object {
        // 가입 입력 규칙의 단일 소스(도메인 불변식). SignUpCommand가 이 값들로 자가 검증합니다.
        // USERNAME_MAX_LENGTH는 DB 스키마 username VARCHAR(50)과 정합합니다(초과 시 raw DB 500 대신 400).
        const val USERNAME_MIN_LENGTH = 3
        const val USERNAME_MAX_LENGTH = 50
        const val PASSWORD_MIN_LENGTH = 8
    }
}

// 권한. JWT의 roles 클레임 및 Spring Security의 ROLE_ 권한과 이름으로 정합합니다(USER/ADMIN).
enum class Role {
    USER,
    ADMIN,
}
