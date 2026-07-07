package demo.board.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

// Spring Data R2DBC 매핑 대상(refresh_tokens 테이블). 단순 값 홀더이며, 도메인 RefreshToken ↔ 이 엔티티 변환은
// RefreshTokenMapper가 담당합니다. tokenHash ↔ token_hash 등 camelCase↔snake_case는 R2DBC 기본 네이밍이 매핑합니다.
@Table("refresh_tokens")
class RefreshTokenR2dbcEntity(
    @Id
    val id: Long? = null,
    val userId: Long,
    val tokenHash: String,
    val expiresAt: LocalDateTime,
    val revokedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
)
