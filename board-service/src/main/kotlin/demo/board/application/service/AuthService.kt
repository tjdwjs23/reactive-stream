package demo.board.application.service

import demo.board.application.port.`in`.AuthTokens
import demo.board.application.port.`in`.LoginCommand
import demo.board.application.port.`in`.LoginUseCase
import demo.board.application.port.`in`.RefreshCommand
import demo.board.application.port.`in`.RefreshTokenUseCase
import demo.board.application.port.`in`.SignUpCommand
import demo.board.application.port.`in`.SignUpUseCase
import demo.board.application.port.out.AuthTokenPort
import demo.board.application.port.out.LoginRateLimiterPort
import demo.board.application.port.out.PasswordEncoderPort
import demo.board.application.port.out.RefreshTokenHashPort
import demo.board.application.port.out.RefreshTokenPort
import demo.board.application.port.out.UserRepositoryPort
import demo.board.domain.exception.DuplicateUsernameException
import demo.board.domain.exception.InvalidCredentialsException
import demo.board.domain.exception.InvalidRefreshTokenException
import demo.board.domain.exception.TooManyLoginAttemptsException
import demo.board.domain.model.RefreshToken
import demo.board.domain.model.Role
import demo.board.domain.model.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

// 회원가입/로그인/토큰 재발급 오케스트레이션. 해싱/토큰 발급/영속화/rate limiting은 각각 out-port에 위임하고,
// 여기서는 흐름만 조립합니다. BoardService와 동일하게 클래스 레벨 @Transactional을 두지 않습니다(단일 문장 오토커밋).
//
// 인증 운영요소:
//  - 로그인 brute-force 방어: LoginRateLimiterPort로 username별 실패를 세고, 임계치 초과 시 429.
//  - Refresh Token(회전+재사용 감지): 로그인/재발급 시 불투명 리프레시 토큰을 새로 발급(회전)하고,
//    이미 폐기된 토큰이 다시 제시되면(재사용) 그 사용자의 모든 토큰을 폐기합니다(세션 탈취 대응).
@Service
class AuthService(
    private val userRepositoryPort: UserRepositoryPort,
    private val passwordEncoderPort: PasswordEncoderPort,
    private val authTokenPort: AuthTokenPort,
    private val refreshTokenPort: RefreshTokenPort,
    private val refreshTokenHashPort: RefreshTokenHashPort,
    private val loginRateLimiterPort: LoginRateLimiterPort,
    // 생성 시각 주입용 시계(도메인이 벽시계를 직접 읽지 않음 — Board 관례와 동일).
    private val clock: Clock,
    @Value("\${board.security.refresh-token.ttl-days:14}") private val refreshTtlDays: Long,
) : SignUpUseCase,
    LoginUseCase,
    RefreshTokenUseCase {
    override suspend fun signUp(command: SignUpCommand): Long {
        // 경쟁 상태에서 두 요청이 동시에 통과할 수 있으나, DB의 UNIQUE(username) 제약이 최종 방어선입니다.
        if (userRepositoryPort.existsByUsername(command.username)) {
            throw DuplicateUsernameException(command.username)
        }
        val user =
            User(
                username = command.username,
                passwordHash = passwordEncoderPort.encode(command.password),
                role = Role.USER, // 가입은 항상 일반 사용자. 관리자는 기동 시 부트스트랩으로만 생성됩니다.
                createdAt = LocalDateTime.now(clock),
            )
        return userRepositoryPort.save(user).id
            ?: error("저장된 사용자는 반드시 id를 가져야 합니다.")
    }

    override suspend fun login(command: LoginCommand): AuthTokens {
        // brute-force 방어: 짧은 시간에 실패가 임계치를 넘은 계정은 자격 검증 이전에 즉시 차단(429).
        if (loginRateLimiterPort.isBlocked(command.username)) {
            throw TooManyLoginAttemptsException()
        }
        // 사용자 미존재/비밀번호 불일치를 동일 예외로 수렴 — 존재 여부가 응답으로 새지 않도록.
        val user = userRepositoryPort.findByUsername(command.username)
        if (user == null || !passwordEncoderPort.matches(command.password, user.passwordHash)) {
            loginRateLimiterPort.recordFailure(command.username)
            throw InvalidCredentialsException()
        }
        // 성공 시 실패 카운터 초기화(정상 사용자가 이전 실패로 잠기지 않도록).
        loginRateLimiterPort.reset(command.username)
        return issueTokens(user)
    }

    override suspend fun refresh(command: RefreshCommand): AuthTokens {
        val presentedHash = refreshTokenHashPort.hash(command.refreshToken)
        val stored = refreshTokenPort.findByHash(presentedHash) ?: throw InvalidRefreshTokenException()
        val now = LocalDateTime.now(clock)

        // 재사용 감지: 이미 폐기된 토큰이 다시 제시됨 = 원문 유출/탈취 의심 → 해당 사용자의 모든 세션을 무효화.
        if (stored.revokedAt != null) {
            refreshTokenPort.revokeAllForUser(stored.userId)
            throw InvalidRefreshTokenException()
        }
        if (stored.isExpired(now)) throw InvalidRefreshTokenException()

        // 회전: 사용한 토큰은 즉시 폐기하고 새 쌍을 발급합니다(1회용 리프레시 토큰).
        refreshTokenPort.revoke(stored.id ?: error("영속화된 리프레시 토큰은 id가 있어야 합니다"))
        val user = userRepositoryPort.findById(stored.userId) ?: throw InvalidRefreshTokenException()
        return issueTokens(user)
    }

    // 액세스 토큰(JWT) 발급 + 새 리프레시 토큰(불투명 원문) 생성/저장 → 둘을 묶어 반환합니다.
    private suspend fun issueTokens(user: User): AuthTokens {
        val access = authTokenPort.issue(user)
        val rawRefresh = refreshTokenHashPort.generateToken()
        val ttl = Duration.ofDays(refreshTtlDays)
        val now = LocalDateTime.now(clock)
        refreshTokenPort.save(
            RefreshToken(
                userId = user.id ?: error("영속화된 사용자만 리프레시 토큰을 가질 수 있습니다"),
                tokenHash = refreshTokenHashPort.hash(rawRefresh),
                expiresAt = now.plus(ttl),
                createdAt = now,
            ),
        )
        return AuthTokens(
            accessToken = access.accessToken,
            tokenType = access.tokenType,
            expiresInSeconds = access.expiresInSeconds,
            refreshToken = rawRefresh,
            refreshExpiresInSeconds = ttl.seconds,
        )
    }
}
