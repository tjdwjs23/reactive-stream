package demo.board.application.service

import demo.board.application.port.`in`.AuthToken
import demo.board.application.port.`in`.LoginCommand
import demo.board.application.port.`in`.RefreshCommand
import demo.board.application.port.`in`.SignUpCommand
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC)
private val NOW: LocalDateTime = LocalDateTime.now(FIXED_CLOCK)

private class AuthFixture {
    val userRepositoryPort = mockk<UserRepositoryPort>()
    val passwordEncoderPort = mockk<PasswordEncoderPort>()
    val authTokenPort = mockk<AuthTokenPort>()
    val refreshTokenPort = mockk<RefreshTokenPort>(relaxed = true)
    val refreshTokenHashPort = mockk<RefreshTokenHashPort>()
    val loginRateLimiterPort = mockk<LoginRateLimiterPort>(relaxed = true)
    val service =
        AuthService(
            userRepositoryPort,
            passwordEncoderPort,
            authTokenPort,
            refreshTokenPort,
            refreshTokenHashPort,
            loginRateLimiterPort,
            FIXED_CLOCK,
            refreshTtlDays = 14,
        )

    val user = User(id = 1L, username = "gildong", passwordHash = "hashed", role = Role.USER, createdAt = NOW)

    // 로그인/재발급 성공 경로의 공통 토큰 발급 스텁(access 발급 + 리프레시 원문 생성/해시/저장).
    fun stubIssue() {
        every { authTokenPort.issue(user) } returns AuthToken(accessToken = "jwt-token", expiresInSeconds = 3600)
        every { refreshTokenHashPort.generateToken() } returns "raw-refresh"
        every { refreshTokenHashPort.hash("raw-refresh") } returns "hash-of-raw-refresh"
        coEvery { refreshTokenPort.save(any()) } answers { firstArg() }
    }
}

class AuthServiceTest :
    BehaviorSpec({

        Given("사용 가능한 username으로 가입할 때") {
            val fixture = AuthFixture()
            val command = SignUpCommand(username = "gildong", password = "password123")
            coEvery { fixture.userRepositoryPort.existsByUsername("gildong") } returns false
            every { fixture.passwordEncoderPort.encode("password123") } returns "hashed"
            coEvery { fixture.userRepositoryPort.save(any()) } returns fixture.user

            When("signUp을 호출하면") {
                val id = fixture.service.signUp(command)

                Then("비밀번호를 인코딩해 ROLE_USER로 저장하고 생성된 id를 반환한다") {
                    id shouldBe 1L
                    coVerify {
                        fixture.userRepositoryPort.save(
                            match { it.username == "gildong" && it.passwordHash == "hashed" && it.role == Role.USER },
                        )
                    }
                }
            }
        }

        Given("이미 존재하는 username으로 가입할 때") {
            val fixture = AuthFixture()
            val command = SignUpCommand(username = "gildong", password = "password123")
            coEvery { fixture.userRepositoryPort.existsByUsername("gildong") } returns true

            When("signUp을 호출하면") {
                Then("DuplicateUsernameException을 던지고 저장하지 않는다") {
                    shouldThrow<DuplicateUsernameException> { fixture.service.signUp(command) }
                    coVerify(exactly = 0) { fixture.userRepositoryPort.save(any()) }
                }
            }
        }

        Given("올바른 자격 증명으로 로그인할 때") {
            val fixture = AuthFixture()
            coEvery { fixture.loginRateLimiterPort.isBlocked("gildong") } returns false
            coEvery { fixture.userRepositoryPort.findByUsername("gildong") } returns fixture.user
            every { fixture.passwordEncoderPort.matches("password123", "hashed") } returns true
            fixture.stubIssue()

            When("login을 호출하면") {
                val tokens = fixture.service.login(LoginCommand("gildong", "password123"))

                Then("액세스+리프레시 토큰을 발급하고 실패 카운터를 초기화한다") {
                    tokens.accessToken shouldBe "jwt-token"
                    tokens.tokenType shouldBe "Bearer"
                    tokens.refreshToken shouldBe "raw-refresh"
                    tokens.refreshExpiresInSeconds shouldBe
                        java.time.Duration
                            .ofDays(14)
                            .seconds
                    coVerify { fixture.loginRateLimiterPort.reset("gildong") }
                    coVerify { fixture.refreshTokenPort.save(match { it.tokenHash == "hash-of-raw-refresh" }) }
                }
            }
        }

        Given("차단된(rate-limited) 계정으로 로그인할 때") {
            val fixture = AuthFixture()
            coEvery { fixture.loginRateLimiterPort.isBlocked("gildong") } returns true

            When("login을 호출하면") {
                Then("자격 검증 전에 TooManyLoginAttemptsException을 던진다") {
                    shouldThrow<TooManyLoginAttemptsException> {
                        fixture.service.login(LoginCommand("gildong", "password123"))
                    }
                    coVerify(exactly = 0) { fixture.userRepositoryPort.findByUsername(any()) }
                }
            }
        }

        Given("존재하지 않는 username으로 로그인할 때") {
            val fixture = AuthFixture()
            coEvery { fixture.loginRateLimiterPort.isBlocked("nobody") } returns false
            coEvery { fixture.userRepositoryPort.findByUsername("nobody") } returns null

            When("login을 호출하면") {
                Then("InvalidCredentialsException을 던지고 실패를 기록한다") {
                    shouldThrow<InvalidCredentialsException> {
                        fixture.service.login(LoginCommand("nobody", "password123"))
                    }
                    coVerify { fixture.loginRateLimiterPort.recordFailure("nobody") }
                }
            }
        }

        Given("비밀번호가 틀린 로그인일 때") {
            val fixture = AuthFixture()
            coEvery { fixture.loginRateLimiterPort.isBlocked("gildong") } returns false
            coEvery { fixture.userRepositoryPort.findByUsername("gildong") } returns fixture.user
            every { fixture.passwordEncoderPort.matches("wrong", "hashed") } returns false

            When("login을 호출하면") {
                Then("InvalidCredentialsException을 던지고 실패를 기록하며 토큰을 발급하지 않는다") {
                    shouldThrow<InvalidCredentialsException> {
                        fixture.service.login(LoginCommand("gildong", "wrong"))
                    }
                    coVerify { fixture.loginRateLimiterPort.recordFailure("gildong") }
                    coVerify(exactly = 0) { fixture.authTokenPort.issue(any()) }
                }
            }
        }

        Given("유효한 리프레시 토큰으로 재발급할 때") {
            val fixture = AuthFixture()
            val active =
                RefreshToken(id = 5L, userId = 1L, tokenHash = "h", expiresAt = NOW.plusDays(7), createdAt = NOW)
            every { fixture.refreshTokenHashPort.hash("presented") } returns "h"
            coEvery { fixture.refreshTokenPort.findByHash("h") } returns active
            coEvery { fixture.userRepositoryPort.findById(1L) } returns fixture.user
            fixture.stubIssue()

            When("refresh를 호출하면") {
                val tokens = fixture.service.refresh(RefreshCommand("presented"))

                Then("기존 토큰을 폐기(회전)하고 새 액세스+리프레시를 발급한다") {
                    tokens.accessToken shouldBe "jwt-token"
                    tokens.refreshToken shouldBe "raw-refresh"
                    coVerify { fixture.refreshTokenPort.revoke(5L) }
                    coVerify { fixture.refreshTokenPort.save(match { it.tokenHash == "hash-of-raw-refresh" }) }
                }
            }
        }

        Given("이미 폐기된 리프레시 토큰이 다시 제시될 때(재사용)") {
            val fixture = AuthFixture()
            val revoked =
                RefreshToken(
                    id = 6L,
                    userId = 2L,
                    tokenHash = "h2",
                    expiresAt = NOW.plusDays(7),
                    revokedAt = NOW.minusDays(1),
                    createdAt = NOW.minusDays(2),
                )
            every { fixture.refreshTokenHashPort.hash("reused") } returns "h2"
            coEvery { fixture.refreshTokenPort.findByHash("h2") } returns revoked

            When("refresh를 호출하면") {
                Then("재사용 감지로 해당 사용자의 모든 토큰을 폐기하고 401을 던진다") {
                    shouldThrow<InvalidRefreshTokenException> { fixture.service.refresh(RefreshCommand("reused")) }
                    coVerify { fixture.refreshTokenPort.revokeAllForUser(2L) }
                    coVerify(exactly = 0) { fixture.authTokenPort.issue(any()) }
                }
            }
        }

        Given("만료된 리프레시 토큰으로 재발급할 때") {
            val fixture = AuthFixture()
            val expired =
                RefreshToken(
                    id = 7L,
                    userId = 3L,
                    tokenHash = "h3",
                    expiresAt = NOW.minusMinutes(1),
                    createdAt = NOW.minusDays(15),
                )
            every { fixture.refreshTokenHashPort.hash("expired") } returns "h3"
            coEvery { fixture.refreshTokenPort.findByHash("h3") } returns expired

            When("refresh를 호출하면") {
                Then("InvalidRefreshTokenException을 던지고 회전하지 않는다") {
                    shouldThrow<InvalidRefreshTokenException> { fixture.service.refresh(RefreshCommand("expired")) }
                    coVerify(exactly = 0) { fixture.refreshTokenPort.revoke(any()) }
                }
            }
        }

        Given("존재하지 않는 리프레시 토큰으로 재발급할 때") {
            val fixture = AuthFixture()
            every { fixture.refreshTokenHashPort.hash("ghost") } returns "h4"
            coEvery { fixture.refreshTokenPort.findByHash("h4") } returns null

            When("refresh를 호출하면") {
                Then("InvalidRefreshTokenException을 던진다") {
                    shouldThrow<InvalidRefreshTokenException> { fixture.service.refresh(RefreshCommand("ghost")) }
                }
            }
        }
    })
