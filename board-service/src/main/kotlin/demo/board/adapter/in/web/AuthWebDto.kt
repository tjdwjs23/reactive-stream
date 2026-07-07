package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.AuthToken

// 회원가입 요청. 검증(길이 규칙)은 SignUpCommand.init이 담당합니다.
data class SignUpRequest(
    val username: String,
    val password: String,
)

data class LoginRequest(
    val username: String,
    val password: String,
)

// 로그인 응답. AuthToken(도메인 경계의 결과)을 웹 표현으로 옮깁니다.
data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
) {
    companion object {
        fun from(token: AuthToken): TokenResponse =
            TokenResponse(
                accessToken = token.accessToken,
                tokenType = token.tokenType,
                expiresInSeconds = token.expiresInSeconds,
            )
    }
}

// 회원가입 응답(생성된 사용자 id).
data class SignUpResponse(
    val id: Long,
)
