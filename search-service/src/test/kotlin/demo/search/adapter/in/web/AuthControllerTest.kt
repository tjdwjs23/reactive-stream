package demo.search.adapter.`in`.web

import demo.search.application.port.`in`.AuthTokens
import demo.search.application.port.`in`.LoginUseCase
import demo.search.application.port.`in`.RefreshTokenUseCase
import demo.search.application.port.`in`.SignUpUseCase
import demo.search.domain.exception.DuplicateUsernameException
import demo.search.domain.exception.InvalidCredentialsException
import demo.search.domain.exception.InvalidRefreshTokenException
import demo.search.domain.exception.TooManyLoginAttemptsException
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

private class AuthControllerFixture {
    val signUpUseCase = mockk<SignUpUseCase>()
    val loginUseCase = mockk<LoginUseCase>()
    val refreshTokenUseCase = mockk<RefreshTokenUseCase>()

    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AuthController(signUpUseCase, loginUseCase, refreshTokenUseCase))
            .setControllerAdvice(GlobalExceptionHandler())
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
            every { fixture.signUpUseCase.signUp(any()) } returns 1L

            When("가입을 요청하면") {
                Then("201 Created와 생성된 id를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"username":"gildong","password":"password123"}"""),
                        ).andExpect(status().isCreated)
                        .andExpect(header().string("Location", "/api/users/1"))
                        .andExpect(jsonPath("$.result.id").value(1))
                }
            }
        }

        Given("규칙 위반 가입 요청(짧은 비밀번호) - POST /api/auth/signup") {
            val fixture = AuthControllerFixture()

            When("8자 미만 비밀번호로 요청하면") {
                Then("커맨드 자가검증이 400 VALIDATION_ERROR로 매핑된다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"username":"gildong","password":"short"}"""),
                        ).andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.result.code").value("VALIDATION_ERROR"))
                }
            }
        }

        Given("중복된 username 가입 요청 - POST /api/auth/signup") {
            val fixture = AuthControllerFixture()
            every { fixture.signUpUseCase.signUp(any()) } throws DuplicateUsernameException("gildong")

            When("가입을 요청하면") {
                Then("409 Conflict와 DUPLICATE_USERNAME을 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"username":"gildong","password":"password123"}"""),
                        ).andExpect(status().isConflict)
                        .andExpect(jsonPath("$.result.code").value("DUPLICATE_USERNAME"))
                }
            }
        }

        Given("올바른 로그인 요청 - POST /api/auth/login") {
            val fixture = AuthControllerFixture()
            every { fixture.loginUseCase.login(any()) } returns tokens()

            When("로그인을 요청하면") {
                Then("200 OK와 액세스+리프레시 토큰을 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"username":"gildong","password":"password123"}"""),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.accessToken").value("jwt-token"))
                        .andExpect(jsonPath("$.result.tokenType").value("Bearer"))
                        .andExpect(jsonPath("$.result.refreshToken").value("raw-refresh"))
                }
            }
        }

        Given("잘못된 자격 증명 로그인 요청 - POST /api/auth/login") {
            val fixture = AuthControllerFixture()
            every { fixture.loginUseCase.login(any()) } throws InvalidCredentialsException()

            When("로그인을 요청하면") {
                Then("401 Unauthorized와 INVALID_CREDENTIALS를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"username":"gildong","password":"wrong-password"}"""),
                        ).andExpect(status().isUnauthorized)
                        .andExpect(jsonPath("$.result.code").value("INVALID_CREDENTIALS"))
                }
            }
        }

        Given("로그인 시도 과다 - POST /api/auth/login") {
            val fixture = AuthControllerFixture()
            every { fixture.loginUseCase.login(any()) } throws TooManyLoginAttemptsException()

            When("차단 상태에서 로그인을 요청하면") {
                Then("429 Too Many Requests와 TOO_MANY_LOGIN_ATTEMPTS를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"username":"gildong","password":"password123"}"""),
                        ).andExpect(status().isTooManyRequests)
                        .andExpect(jsonPath("$.result.code").value("TOO_MANY_LOGIN_ATTEMPTS"))
                }
            }
        }

        Given("유효한 리프레시 요청 - POST /api/auth/refresh") {
            val fixture = AuthControllerFixture()
            every { fixture.refreshTokenUseCase.refresh(any()) } returns tokens()

            When("재발급을 요청하면") {
                Then("200 OK와 새 토큰 쌍을 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"refreshToken":"raw-refresh"}"""),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.accessToken").value("jwt-token"))
                        .andExpect(jsonPath("$.result.refreshToken").value("raw-refresh"))
                }
            }
        }

        Given("무효한 리프레시 요청 - POST /api/auth/refresh") {
            val fixture = AuthControllerFixture()
            every { fixture.refreshTokenUseCase.refresh(any()) } throws InvalidRefreshTokenException()

            When("재발급을 요청하면") {
                Then("401 Unauthorized와 INVALID_REFRESH_TOKEN을 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"refreshToken":"bad"}"""),
                        ).andExpect(status().isUnauthorized)
                        .andExpect(jsonPath("$.result.code").value("INVALID_REFRESH_TOKEN"))
                }
            }
        }
    })
