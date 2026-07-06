package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.AuthToken
import demo.board.application.port.`in`.LoginUseCase
import demo.board.application.port.`in`.SignUpUseCase
import demo.board.domain.exception.DuplicateUsernameException
import demo.board.domain.exception.InvalidCredentialsException
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

private class AuthControllerFixture {
    val signUpUseCase = mockk<SignUpUseCase>()
    val loginUseCase = mockk<LoginUseCase>()

    val client: WebTestClient =
        WebTestClient
            .bindToController(AuthController(signUpUseCase, loginUseCase))
            .controllerAdvice(GlobalExceptionHandler())
            .build()
}

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
            coEvery { fixture.loginUseCase.login(any()) } returns
                AuthToken(accessToken = "jwt-token", expiresInSeconds = 3600)

            When("로그인을 요청하면") {
                Then("200 OK와 액세스 토큰을 반환한다") {
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
    })
