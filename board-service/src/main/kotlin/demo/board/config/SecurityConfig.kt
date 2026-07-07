package demo.board.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import reactor.core.publisher.Mono
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// 리액티브 Spring Security 설정. 자체 발급 JWT(HS256 대칭키)로 stateless 인증합니다.
// 정책: 읽기(GET)·문서·actuator는 공개, 게시글 쓰기는 인증, reindex/flush/archive(admin)는 ROLE_ADMIN.
@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    @Value("\${board.security.jwt.secret:}") private val configuredSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 발급/검증에 공유하는 HMAC 키. 미설정 시 재기동 간 토큰이 유지되도록 고정 dev 기본값을 쓰되 경고합니다.
    private val secretKey: SecretKey = resolveSecretKey()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    // 토큰 발급기(NimbusJwtTokenAdapter가 주입받아 서명). 대칭키를 JWKSource로 감싸 HS256 서명합니다.
    @Bean
    fun jwtEncoder(): JwtEncoder {
        val jwkSource: JWKSource<SecurityContext> = ImmutableSecret(secretKey)
        return NimbusJwtEncoder(jwkSource)
    }

    // 리소스 서버가 Bearer 토큰을 검증하는 디코더. 같은 대칭키 + HS256으로 서명을 확인합니다.
    @Bean
    fun jwtDecoder(): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder
            .withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http
            // 상태를 서버에 두지 않는 토큰 인증이라 CSRF/폼로그인/HTTP Basic은 끕니다(REST API + 무상태).
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeExchange { ex ->
                ex
                    // 인증 없이 가입/로그인 가능해야 최초 토큰을 받을 수 있습니다.
                    .pathMatchers(HttpMethod.POST, "/api/auth/**")
                    .permitAll()
                    // 운영 트리거는 ROLE_ADMIN. reindex는 /api/boards 하위라 일반 POST 규칙보다 먼저 선언합니다.
                    .pathMatchers(HttpMethod.POST, "/api/boards/search/reindex")
                    .hasRole("ADMIN")
                    .pathMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    // 게시글 쓰기는 인증 필요(역할 무관 — 수정/삭제는 인증된 사용자면 누구나).
                    .pathMatchers(HttpMethod.POST, "/api/boards/**")
                    .authenticated()
                    .pathMatchers(HttpMethod.PUT, "/api/boards/**")
                    .authenticated()
                    .pathMatchers(HttpMethod.DELETE, "/api/boards/**")
                    .authenticated()
                    // 그 외(GET 단건/목록/검색, /actuator, /swagger-ui, /v3/api-docs, 미존재 경로의 404)는 공개.
                    .anyExchange()
                    .permitAll()
            }.oauth2ResourceServer { rs ->
                rs.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
            }
        return http.build()
    }

    // JWT의 roles 클레임(["USER"] / ["ADMIN"])을 Spring Security 권한(ROLE_USER / ROLE_ADMIN)으로 변환합니다.
    // hasRole("ADMIN")은 "ROLE_ADMIN" 권한을 요구하므로 접두사를 ROLE_로 맞춥니다.
    private fun jwtAuthenticationConverter(): Converter<Jwt, Mono<AbstractAuthenticationToken>> {
        val authoritiesConverter =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthorityPrefix("ROLE_")
                setAuthoritiesClaimName("roles")
            }
        val converter =
            JwtAuthenticationConverter().apply {
                setJwtGrantedAuthoritiesConverter(authoritiesConverter)
            }
        return ReactiveJwtAuthenticationConverterAdapter(converter)
    }

    private fun resolveSecretKey(): SecretKey {
        val raw =
            if (configuredSecret.isBlank()) {
                log.warn(
                    "board.security.jwt.secret is not set — using an INSECURE dev default. " +
                        "Set BOARD_JWT_SECRET (>=32 bytes) in non-local environments.",
                )
                DEV_SECRET
            } else {
                require(configuredSecret.toByteArray().size >= 32) {
                    "board.security.jwt.secret must be at least 32 bytes for HS256"
                }
                configuredSecret
            }
        return SecretKeySpec(raw.toByteArray(), "HmacSHA256")
    }

    private companion object {
        // 로컬/개발 전용 고정 키(≥32byte). 운영에서는 반드시 BOARD_JWT_SECRET로 덮어써야 합니다.
        const val DEV_SECRET = "dev-only-insecure-jwt-secret-change-me-please-32b+"
    }
}
