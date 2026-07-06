package demo.board.application.service

import demo.board.application.port.`in`.AuthToken
import demo.board.application.port.`in`.LoginCommand
import demo.board.application.port.`in`.SignUpCommand
import demo.board.application.port.out.AuthTokenPort
import demo.board.application.port.out.PasswordEncoderPort
import demo.board.application.port.out.UserRepositoryPort
import demo.board.domain.exception.DuplicateUsernameException
import demo.board.domain.exception.InvalidCredentialsException
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
import java.time.LocalDateTime

private class AuthFixture {
    val userRepositoryPort = mockk<UserRepositoryPort>()
    val passwordEncoderPort = mockk<PasswordEncoderPort>()
    val authTokenPort = mockk<AuthTokenPort>()
    val service = AuthService(userRepositoryPort, passwordEncoderPort, authTokenPort, Clock.systemDefaultZone())
}

class AuthServiceTest :
    BehaviorSpec({

        Given("사용 가능한 username으로 가입할 때") {
            val fixture = AuthFixture()
            val command = SignUpCommand(username = "gildong", password = "password123")
            coEvery { fixture.userRepositoryPort.existsByUsername("gildong") } returns false
            every { fixture.passwordEncoderPort.encode("password123") } returns "hashed"
            coEvery { fixture.userRepositoryPort.save(any()) } returns
                User(
                    id = 1L,
                    username = "gildong",
                    passwordHash = "hashed",
                    role = Role.USER,
                    createdAt = LocalDateTime.now(),
                )

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
            val user =
                User(
                    id = 1L,
                    username = "gildong",
                    passwordHash = "hashed",
                    role = Role.USER,
                    createdAt = LocalDateTime.now(),
                )
            coEvery { fixture.userRepositoryPort.findByUsername("gildong") } returns user
            every { fixture.passwordEncoderPort.matches("password123", "hashed") } returns true
            every { fixture.authTokenPort.issue(user) } returns
                AuthToken(accessToken = "jwt-token", expiresInSeconds = 3600)

            When("login을 호출하면") {
                val token = fixture.service.login(LoginCommand("gildong", "password123"))

                Then("토큰을 발급해 반환한다") {
                    token.accessToken shouldBe "jwt-token"
                    token.tokenType shouldBe "Bearer"
                    coVerify { fixture.authTokenPort.issue(user) }
                }
            }
        }

        Given("존재하지 않는 username으로 로그인할 때") {
            val fixture = AuthFixture()
            coEvery { fixture.userRepositoryPort.findByUsername("nobody") } returns null

            When("login을 호출하면") {
                Then("InvalidCredentialsException을 던진다") {
                    shouldThrow<InvalidCredentialsException> {
                        fixture.service.login(LoginCommand("nobody", "password123"))
                    }
                }
            }
        }

        Given("비밀번호가 틀린 로그인일 때") {
            val fixture = AuthFixture()
            val user =
                User(
                    id = 1L,
                    username = "gildong",
                    passwordHash = "hashed",
                    role = Role.USER,
                    createdAt = LocalDateTime.now(),
                )
            coEvery { fixture.userRepositoryPort.findByUsername("gildong") } returns user
            every { fixture.passwordEncoderPort.matches("wrong", "hashed") } returns false

            When("login을 호출하면") {
                Then("InvalidCredentialsException을 던지고 토큰을 발급하지 않는다") {
                    shouldThrow<InvalidCredentialsException> {
                        fixture.service.login(LoginCommand("gildong", "wrong"))
                    }
                    coVerify(exactly = 0) { fixture.authTokenPort.issue(any()) }
                }
            }
        }
    })
