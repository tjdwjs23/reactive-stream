package demo.search.adapter.out.security

import demo.search.application.port.`in`.AuthToken
import demo.search.application.port.out.AuthTokenPort
import demo.search.domain.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

// AuthTokenPort의 구현. Spring Security의 JwtEncoder(SecurityConfig에서 대칭키 HS256으로 정의)로 서명합니다.
// 클레임: sub=사용자 id(리소스 서버가 인증 principal의 subject로 사용 → AuthenticatedUserProvider가 작성자 id로 읽음),
//         username(표시용), roles(권한 매핑용 — SecurityConfig의 컨버터가 ROLE_ 권한으로 변환).
@Component
class NimbusJwtTokenAdapter(
    private val jwtEncoder: JwtEncoder,
    private val clock: Clock,
    @Value("\${board.security.jwt.access-token-ttl-minutes:60}") private val ttlMinutes: Long,
) : AuthTokenPort {
    override fun issue(user: User): AuthToken {
        val userId = user.id ?: error("영속화되지 않은 사용자(id가 null)에게는 토큰을 발급할 수 없습니다.")
        val now: Instant = clock.instant()
        val ttl = Duration.ofMinutes(ttlMinutes)
        val claims =
            JwtClaimsSet
                .builder()
                .subject(userId.toString())
                .claim("username", user.username)
                .claim("roles", listOf(user.role.name))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return AuthToken(accessToken = token, expiresInSeconds = ttl.seconds)
    }
}
