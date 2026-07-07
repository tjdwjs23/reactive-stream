package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.LoginCommand
import demo.board.application.port.`in`.LoginUseCase
import demo.board.application.port.`in`.RefreshCommand
import demo.board.application.port.`in`.RefreshTokenUseCase
import demo.board.application.port.`in`.SignUpCommand
import demo.board.application.port.`in`.SignUpUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

// 인증 엔드포인트. BoardController와 동일하게 UseCase 인터페이스에만 의존합니다(구현체 아님).
// 이 경로들은 SecurityConfig에서 permitAll이라 토큰 없이 호출할 수 있습니다.
@Tag(name = "Auth", description = "회원가입 및 로그인(JWT 발급)")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val signUpUseCase: SignUpUseCase,
    private val loginUseCase: LoginUseCase,
    private val refreshTokenUseCase: RefreshTokenUseCase,
) {
    @Operation(summary = "회원가입", description = "username/password(8자 이상)로 사용자를 만들고 201 + 생성된 id를 반환합니다.")
    @PostMapping("/signup")
    suspend fun signUp(
        @RequestBody request: SignUpRequest,
    ): ResponseEntity<SuccessResponse<SignUpResponse>> {
        val id = signUpUseCase.signUp(SignUpCommand(username = request.username, password = request.password))
        return SuccessResponse.created(SignUpResponse(id), URI.create("/api/users/$id"))
    }

    @Operation(
        summary = "로그인",
        description = "자격 증명을 검증하고 액세스 토큰(JWT) + 리프레시 토큰을 발급합니다. 실패 시 401, 반복 실패 시 429.",
    )
    @PostMapping("/login")
    suspend fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<SuccessResponse<TokenResponse>> {
        val tokens = loginUseCase.login(LoginCommand(username = request.username, password = request.password))
        return SuccessResponse.ok(TokenResponse.from(tokens))
    }

    @Operation(
        summary = "토큰 재발급",
        description = "리프레시 토큰으로 새 액세스+리프레시 토큰을 발급합니다(회전). 무효/만료/재사용 시 401.",
    )
    @PostMapping("/refresh")
    suspend fun refresh(
        @RequestBody request: RefreshRequest,
    ): ResponseEntity<SuccessResponse<TokenResponse>> {
        val tokens = refreshTokenUseCase.refresh(RefreshCommand(refreshToken = request.refreshToken))
        return SuccessResponse.ok(TokenResponse.from(tokens))
    }
}
