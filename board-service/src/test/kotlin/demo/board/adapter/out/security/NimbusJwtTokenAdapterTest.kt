package demo.board.adapter.out.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import demo.board.domain.model.Role
import demo.board.domain.model.User
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.LocalDateTime
import javax.crypto.spec.SecretKeySpec

// 발급기(NimbusJwtTokenAdapter)가 만든 토큰을 같은 대칭키의 디코더로 되읽어 클레임/만료를 검증합니다.
// Spring 컨텍스트 없이 인코더/디코더를 직접 조립하는 순수 단위 테스트입니다.
class NimbusJwtTokenAdapterTest :
    BehaviorSpec({
        val secretKey = SecretKeySpec("test-secret-key-for-hs256-at-least-32b!".toByteArray(), "HmacSHA256")
        val jwkSource: JWKSource<SecurityContext> = ImmutableSecret(secretKey)
        val encoder = NimbusJwtEncoder(jwkSource)
        val decoder =
            NimbusJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
        // 실제 시스템 시계를 씁니다 — 과거로 고정하면 발급 토큰의 exp가 실행 시점보다 앞서 디코더가 만료로 거부합니다.
        val clock = Clock.systemUTC()

        Given("사용자와 60분 TTL이 주어졌을 때") {
            val adapter = NimbusJwtTokenAdapter(encoder, clock, ttlMinutes = 60)
            val user =
                User(
                    id = 42L,
                    username = "gildong",
                    passwordHash = "irrelevant",
                    role = Role.ADMIN,
                    createdAt = LocalDateTime.now(),
                )

            When("issue로 토큰을 발급하고 디코딩하면") {
                val token = adapter.issue(user)
                val jwt = decoder.decode(token.accessToken)

                Then("sub=사용자 id, username/roles 클레임과 만료(발급+60분)가 정확하다") {
                    token.tokenType shouldBe "Bearer"
                    token.expiresInSeconds shouldBe 3600L
                    jwt.subject shouldBe "42"
                    jwt.getClaimAsString("username") shouldBe "gildong"
                    jwt.getClaimAsStringList("roles") shouldBe listOf("ADMIN")
                    // 절대 시각 대신 관계로 검증: exp = iat + 3600초
                    (jwt.expiresAt!!.epochSecond - jwt.issuedAt!!.epochSecond) shouldBe 3600L
                }
            }
        }
    })
