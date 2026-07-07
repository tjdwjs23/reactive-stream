package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.AuthTokens

// 회원가입 요청. 검증(길이 규칙)은 SignUpCommand.init이 담당합니다.
data class SignUpRequest(
    val username: String,
    val password: String,
)

data class LoginRequest(
    val username: String,
    val password: String,
)

// 토큰 재발급 요청. 로그인/직전 재발급에서 받은 리프레시 토큰(원문)을 담습니다.
data class RefreshRequest(
    val refreshToken: String,
)

// 로그인/재발급 응답. AuthTokens(도메인 경계의 결과)를 웹 표현으로 옮깁니다.
data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
) {
    companion object {
        fun from(tokens: AuthTokens): TokenResponse =
            TokenResponse(
                accessToken = tokens.accessToken,
                tokenType = tokens.tokenType,
                expiresInSeconds = tokens.expiresInSeconds,
                refreshToken = tokens.refreshToken,
                refreshExpiresInSeconds = tokens.refreshExpiresInSeconds,
            )
    }
}

// 회원가입 응답(생성된 사용자 id).
data class SignUpResponse(
    val id: Long,
)
