package demo.board.domain.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

// 순수 도메인 규칙(만료/유효) 단위 테스트 — 프레임워크/컨테이너 불필요.
class RefreshTokenTest :
    StringSpec({

        val now = LocalDateTime.of(2026, 7, 7, 0, 0)

        fun token(
            expiresAt: LocalDateTime,
            revokedAt: LocalDateTime? = null,
        ) = RefreshToken(
            id = 1L,
            userId = 1L,
            tokenHash = "h",
            expiresAt = expiresAt,
            revokedAt = revokedAt,
            createdAt = now,
        )

        "만료 시각 이후면 isExpired=true (경계 포함)" {
            token(expiresAt = now.minusSeconds(1)).isExpired(now) shouldBe true
            token(expiresAt = now).isExpired(now) shouldBe true // 정각도 만료로 본다
            token(expiresAt = now.plusSeconds(1)).isExpired(now) shouldBe false
        }

        "폐기되지 않았고 만료도 아니면 isActive=true" {
            token(expiresAt = now.plusDays(1)).isActive(now) shouldBe true
        }

        "폐기됐거나 만료면 isActive=false" {
            token(expiresAt = now.plusDays(1), revokedAt = now.minusHours(1)).isActive(now) shouldBe false
            token(expiresAt = now.minusDays(1)).isActive(now) shouldBe false
        }
    })
