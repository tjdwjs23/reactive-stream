package demo.board.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// (서블릿) Spring Security 설정. 자체 발급 JWT(HS256 대칭키)로 stateless 인증합니다. MVC + 가상 스레드 스택이라
// 필터 체인은 SecurityFilterChain(서블릿)으로 구성하고, 인증 컨텍스트는 ThreadLocal(SecurityContextHolder)로 흐릅니다.
// 정책: 읽기(GET)·문서·actuator는 공개, 게시글 쓰기는 인증, reindex/flush/archive(admin)는 ROLE_ADMIN.
@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${board.security.jwt.secret:}") private val configuredSecret: String,
    // 허용 오리진(쉼표 구분). SPA 등 브라우저 클라이언트가 다른 오리진에서 API를 호출할 수 있게 CORS를 명시합니다.
    // 기본은 로컬 프런트(3000). 운영은 BOARD_CORS_ALLOWED_ORIGINS로 실제 도메인을 주입합니다.
    @Value("\${board.security.cors.allowed-origins:http://localhost:3000}") private val allowedOrigins: String,
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
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder
            .withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 브라우저 클라이언트(SPA)의 교차 오리진 호출 허용. 아래 corsConfigurationSource 정책을 적용합니다.
            .cors { it.configurationSource(corsConfigurationSource()) }
            // 상태를 서버에 두지 않는 토큰 인증이라 CSRF/폼로그인/HTTP Basic은 끄고 세션도 만들지 않습니다.
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // 인증 없이 가입/로그인 가능해야 최초 토큰을 받을 수 있습니다.
                    .requestMatchers(HttpMethod.POST, "/api/auth/**")
                    .permitAll()
                    // 운영 트리거는 ROLE_ADMIN. reindex는 /api/boards 하위라 일반 POST 규칙보다 먼저 선언합니다.
                    .requestMatchers(HttpMethod.POST, "/api/boards/search/reindex")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    // 게시글 쓰기는 인증 필요(역할 무관 — 수정/삭제는 인증된 사용자면 누구나, 소유권은 서비스가 검사).
                    .requestMatchers(HttpMethod.POST, "/api/boards/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/boards/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/boards/**")
                    .authenticated()
                    // 그 외(GET 단건/목록/검색, /actuator, /swagger-ui, /v3/api-docs, 미존재 경로의 404)는 공개.
                    .anyRequest()
                    .permitAll()
            }.oauth2ResourceServer { rs ->
                rs.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
            }
        return http.build()
    }

    // CORS 정책. 허용 오리진은 설정에서 주입(쉼표 구분), 자격증명(Authorization 헤더) 허용, 표준 메서드 허용.
    // allowCredentials=true라 오리진에 와일드카드(*)는 쓸 수 없어(스펙 제약) 명시적 오리진 목록을 씁니다.
    private fun corsConfigurationSource(): CorsConfigurationSource {
        val config =
            CorsConfiguration().apply {
                allowedOrigins =
                    this@SecurityConfig
                        .allowedOrigins
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                allowCredentials = true
                maxAge = 3600
            }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    // JWT의 roles 클레임(["USER"] / ["ADMIN"])을 Spring Security 권한(ROLE_USER / ROLE_ADMIN)으로 변환합니다.
    // hasRole("ADMIN")은 "ROLE_ADMIN" 권한을 요구하므로 접두사를 ROLE_로 맞춥니다.
    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val authoritiesConverter =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthorityPrefix("ROLE_")
                setAuthoritiesClaimName("roles")
            }
        return JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(authoritiesConverter)
        }
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
