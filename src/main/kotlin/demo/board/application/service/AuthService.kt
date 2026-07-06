package demo.board.application.service

import demo.board.application.port.`in`.AuthToken
import demo.board.application.port.`in`.LoginCommand
import demo.board.application.port.`in`.LoginUseCase
import demo.board.application.port.`in`.SignUpCommand
import demo.board.application.port.`in`.SignUpUseCase
import demo.board.application.port.out.AuthTokenPort
import demo.board.application.port.out.PasswordEncoderPort
import demo.board.application.port.out.UserRepositoryPort
import demo.board.domain.exception.DuplicateUsernameException
import demo.board.domain.exception.InvalidCredentialsException
import demo.board.domain.model.Role
import demo.board.domain.model.User
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime

// 회원가입/로그인 오케스트레이션. 해싱/토큰 발급/영속화는 각각 out-port에 위임하고,
// 여기서는 "중복 검사 → 인코딩 → 저장", "조회 → 비밀번호 대조 → 토큰 발급" 흐름만 조립합니다.
// BoardService와 동일하게 클래스 레벨 @Transactional을 두지 않습니다(단일 문장 오토커밋).
@Service
class AuthService(
    private val userRepositoryPort: UserRepositoryPort,
    private val passwordEncoderPort: PasswordEncoderPort,
    private val authTokenPort: AuthTokenPort,
    // 생성 시각 주입용 시계(도메인이 벽시계를 직접 읽지 않음 — Board 관례와 동일).
    private val clock: Clock,
) : SignUpUseCase,
    LoginUseCase {
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
            ?: error("saved user must have an id")
    }

    override suspend fun login(command: LoginCommand): AuthToken {
        // 사용자 미존재/비밀번호 불일치를 동일 예외로 수렴 — 존재 여부가 응답으로 새지 않도록.
        val user =
            userRepositoryPort.findByUsername(command.username)
                ?: throw InvalidCredentialsException()
        if (!passwordEncoderPort.matches(command.password, user.passwordHash)) {
            throw InvalidCredentialsException()
        }
        return authTokenPort.issue(user)
    }
}
