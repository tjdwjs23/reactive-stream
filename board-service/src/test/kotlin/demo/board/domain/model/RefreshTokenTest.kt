package demo.board.domain.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

// 순수 도메인 규칙(만료/유효) 단위 테스트 — 프레임워크/컨테이너 불필요.
class RefreshTokenTest :
    StringSpec({

        val now = LocalDateTime.of(2026, 7, 7, 0, 0)

        fun token(expiresAt: LocalDateTime) =
            RefreshToken(
                id = 1L,
                userId = 1L,
                tokenHash = "h",
                expiresAt = expiresAt,
                createdAt = now,
            )

        "만료 시각 이후면 isExpired=true (경계 포함)" {
            token(expiresAt = now.minusSeconds(1)).isExpired(now) shouldBe true
            token(expiresAt = now).isExpired(now) shouldBe true // 정각도 만료로 본다
            token(expiresAt = now.plusSeconds(1)).isExpired(now) shouldBe false
        }
    })
