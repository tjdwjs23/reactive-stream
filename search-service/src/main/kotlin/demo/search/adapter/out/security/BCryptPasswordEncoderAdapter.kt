package demo.search.adapter.out.security

import demo.search.application.port.out.PasswordEncoderPort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

// PasswordEncoderPort의 구현. Spring Security의 PasswordEncoder(SecurityConfig에서 BCrypt로 정의) 빈에 위임합니다.
// 애플리케이션 계층은 이 클래스도, Spring Security도 모른 채 PasswordEncoderPort 인터페이스에만 의존합니다.
@Component
class BCryptPasswordEncoderAdapter(
    private val passwordEncoder: PasswordEncoder,
) : PasswordEncoderPort {
    // Spring의 encode 시그니처는 플랫폼 타입(nullable)로 보이나, non-null 입력에 대해 null을 반환하지 않습니다.
    override fun encode(rawPassword: String): String =
        passwordEncoder.encode(rawPassword)
            ?: error("비밀번호 인코더가 null을 반환했습니다.")

    override fun matches(
        rawPassword: String,
        encodedPassword: String,
    ): Boolean = passwordEncoder.matches(rawPassword, encodedPassword)
}
