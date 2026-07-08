package demo.board.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// JPA 매핑 대상(refresh_tokens 테이블). 도메인 RefreshToken ↔ 이 엔티티 변환은 RefreshTokenMapper가 담당합니다.
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "user_id", nullable = false)
    var userId: Long,
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: LocalDateTime,
    @Column(name = "revoked_at")
    var revokedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,
)
