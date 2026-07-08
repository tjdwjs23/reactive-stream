package demo.search.application.port.`in`

import demo.search.domain.model.User.Companion.PASSWORD_MIN_LENGTH
import demo.search.domain.model.User.Companion.USERNAME_MAX_LENGTH
import demo.search.domain.model.User.Companion.USERNAME_MIN_LENGTH

// 회원가입 유즈케이스. 새 사용자를 만들고(항상 ROLE_USER), 이후 로그인해 토큰을 받습니다.
interface SignUpUseCase {
    fun signUp(command: SignUpCommand): Long // 생성된 사용자 id
}

data class SignUpCommand(
    val username: String,
    val password: String,
) {
    init {
        // 자가 검증(Self-Validating) — Board의 CreateBoardCommand와 동일한 관례.
        // 길이 규칙의 단일 소스는 도메인(User)이며, 초과 username은 여기서 400으로 거릅니다.
        require(username.length in USERNAME_MIN_LENGTH..USERNAME_MAX_LENGTH) {
            "사용자명은 ${USERNAME_MIN_LENGTH}자에서 ${USERNAME_MAX_LENGTH}자 사이여야 합니다."
        }
        require(password.length >= PASSWORD_MIN_LENGTH) {
            "비밀번호는 ${PASSWORD_MIN_LENGTH}자 이상이어야 합니다."
        }
    }
}

// 로그인 유즈케이스. 자격 증명을 검증하고 액세스 토큰 + 리프레시 토큰을 발급합니다.
interface LoginUseCase {
    fun login(command: LoginCommand): AuthTokens
}

// 로그인 입력은 자가 검증하지 않습니다 — 형식 검증으로 "사용자 존재/비존재"를 유추당하지 않도록,
// 잘못된 값은 모두 동일한 InvalidCredentialsException(401)로 수렴시킵니다.
data class LoginCommand(
    val username: String,
    val password: String,
)

// 리프레시 유즈케이스. 유효한 리프레시 토큰을 제시하면 새 액세스+리프레시 토큰을 발급합니다(회전).
// 이미 폐기된 토큰이 다시 제시되면(재사용) 해당 사용자의 세션을 전부 무효화합니다(탈취 대응).
interface RefreshTokenUseCase {
    fun refresh(command: RefreshCommand): AuthTokens
}

data class RefreshCommand(
    val refreshToken: String,
)

// 발급된 액세스 토큰(단건). AuthTokenPort.issue의 반환 타입 — 서비스가 이를 AuthTokens로 조립합니다.
// tokenType은 관례상 "Bearer", expiresInSeconds는 만료까지 남은 초.
data class AuthToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
)

// 로그인/리프레시가 클라이언트에 돌려주는 토큰 쌍. accessToken은 짧은 수명(요청 인증),
// refreshToken은 긴 수명(access 재발급용, 불투명 랜덤 원문 — 서버는 해시만 보관).
data class AuthTokens(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
)
