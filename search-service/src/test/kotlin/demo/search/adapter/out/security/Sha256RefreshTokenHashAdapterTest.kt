package demo.search.adapter.out.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

// 순수 JDK(SecureRandom + SHA-256) 구현이라 컨테이너 없이 검증합니다.
class Sha256RefreshTokenHashAdapterTest :
    StringSpec({

        val adapter = Sha256RefreshTokenHashAdapter()

        "generateToken은 매번 추측 불가능한 서로 다른 토큰을 만든다" {
            val a = adapter.generateToken()
            val b = adapter.generateToken()
            a shouldNotBe b
            // 256비트를 URL-safe Base64(무패딩)로 인코딩 → 40자 이상, URL-safe 문자만.
            a shouldMatch Regex("^[A-Za-z0-9_-]{40,}$")
        }

        "hash는 결정적이며 64자 소문자 hex를 반환한다" {
            val h1 = adapter.hash("raw-token")
            val h2 = adapter.hash("raw-token")
            h1 shouldBe h2 // 같은 입력 → 같은 해시(조회 키로 사용 가능)
            h1 shouldMatch Regex("^[0-9a-f]{64}$") // SHA-256 = 32바이트 = 64 hex chars
        }

        "다른 입력은 다른 해시를 만든다" {
            adapter.hash("token-a") shouldNotBe adapter.hash("token-b")
        }
    })
