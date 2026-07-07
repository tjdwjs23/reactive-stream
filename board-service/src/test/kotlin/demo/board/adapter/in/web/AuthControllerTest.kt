package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.AuthTokens
import demo.board.application.port.`in`.LoginUseCase
import demo.board.application.port.`in`.RefreshTokenUseCase
import demo.board.application.port.`in`.SignUpUseCase
import demo.board.domain.exception.DuplicateUsernameException
import demo.board.domain.exception.InvalidCredentialsException
import demo.board.domain.exception.InvalidRefreshTokenException
import demo.board.domain.exception.TooManyLoginAttemptsException
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

private class AuthControllerFixture {
    val signUpUseCase = mockk<SignUpUseCase>()
    val loginUseCase = mockk<LoginUseCase>()
    val refreshTokenUseCase = mockk<RefreshTokenUseCase>()

    val client: WebTestClient =
        WebTestClient
            .bindToController(AuthController(signUpUseCase, loginUseCase, refreshTokenUseCase))
            .controllerAdvice(GlobalExceptionHandler())
            .build()
}

private fun tokens() =
    AuthTokens(
        accessToken = "jwt-token",
        expiresInSeconds = 3600,
        refreshToken = "raw-refresh",
        refreshExpiresInSeconds = 1209600,
    )

class AuthControllerTest :
    BehaviorSpec({

        Given("유효한 가입 요청 - POST /api/auth/signup") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.signUpUseCase.signUp(any()) } returns 1L

            When("가입을 요청하면") {
                Then("201 Created와 생성된 id를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"username":"gildong","password":"password123"}""")
                        .exchange()
                        .expectStatus()
                        .isCreated
                        .expectHeader()
                        .valueEquals("Location", "/api/users/1")
                        .expectBody()
                        .jsonPath("$.result.id")
                        .isEqualTo(1)
                }
            }
        }

        Given("규칙 위반 가입 요청(짧은 비밀번호) - POST /api/auth/signup") {
            val fixture = AuthControllerFixture()

            When("8자 미만 비밀번호로 요청하면") {
                Then("커맨드 자가검증이 400 VALIDATION_ERROR로 매핑된다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"username":"gildong","password":"short"}""")
                        .exchange()
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("VALIDATION_ERROR")
                }
            }
        }

        Given("중복된 username 가입 요청 - POST /api/auth/signup") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.signUpUseCase.signUp(any()) } throws DuplicateUsernameException("gildong")

            When("가입을 요청하면") {
                Then("409 Conflict와 DUPLICATE_USERNAME을 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"username":"gildong","password":"password123"}""")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(409)
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("DUPLICATE_USERNAME")
                }
            }
        }

        Given("올바른 로그인 요청 - POST /api/auth/login") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.loginUseCase.login(any()) } returns tokens()

            When("로그인을 요청하면") {
                Then("200 OK와 액세스+리프레시 토큰을 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"username":"gildong","password":"password123"}""")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.result.accessToken")
                        .isEqualTo("jwt-token")
                        .jsonPath("$.result.tokenType")
                        .isEqualTo("Bearer")
                        .jsonPath("$.result.refreshToken")
                        .isEqualTo("raw-refresh")
                }
            }
        }

        Given("잘못된 자격 증명 로그인 요청 - POST /api/auth/login") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.loginUseCase.login(any()) } throws InvalidCredentialsException()

            When("로그인을 요청하면") {
                Then("401 Unauthorized와 INVALID_CREDENTIALS를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"username":"gildong","password":"wrong-password"}""")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("INVALID_CREDENTIALS")
                }
            }
        }

        Given("로그인 시도 과다 - POST /api/auth/login") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.loginUseCase.login(any()) } throws TooManyLoginAttemptsException()

            When("차단 상태에서 로그인을 요청하면") {
                Then("429 Too Many Requests와 TOO_MANY_LOGIN_ATTEMPTS를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"username":"gildong","password":"password123"}""")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(429)
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("TOO_MANY_LOGIN_ATTEMPTS")
                }
            }
        }

        Given("유효한 리프레시 요청 - POST /api/auth/refresh") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.refreshTokenUseCase.refresh(any()) } returns tokens()

            When("재발급을 요청하면") {
                Then("200 OK와 새 토큰 쌍을 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"refreshToken":"raw-refresh"}""")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.result.accessToken")
                        .isEqualTo("jwt-token")
                        .jsonPath("$.result.refreshToken")
                        .isEqualTo("raw-refresh")
                }
            }
        }

        Given("무효한 리프레시 요청 - POST /api/auth/refresh") {
            val fixture = AuthControllerFixture()
            coEvery { fixture.refreshTokenUseCase.refresh(any()) } throws InvalidRefreshTokenException()

            When("재발급을 요청하면") {
                Then("401 Unauthorized와 INVALID_REFRESH_TOKEN을 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"refreshToken":"bad"}""")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("INVALID_REFRESH_TOKEN")
                }
            }
        }
    })
