package demo.board.application.port.out

// 비밀번호 해싱 out-port. 애플리케이션이 특정 인코더(BCrypt 등)나 Spring Security에 직접 의존하지 않도록
// 인터페이스로 감쌉니다. 구현은 adapter.out.security에서 Spring의 PasswordEncoder에 위임합니다.
interface PasswordEncoderPort {
    fun encode(rawPassword: String): String

    fun matches(
        rawPassword: String,
        encodedPassword: String,
    ): Boolean
}
