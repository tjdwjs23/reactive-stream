package demo.reactivestream.adapter.`in`.web

import demo.reactivestream.application.port.`in`.FlushBoardViewCountsUseCase
import demo.reactivestream.application.port.`in`.FlushViewCountsResult
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.springframework.test.web.reactive.server.WebTestClient

private class AdminControllerFixture(
    adminToken: String = "",
) {
    val flushUseCase = mockk<FlushBoardViewCountsUseCase>()

    val client: WebTestClient =
        WebTestClient
            .bindToController(
                AdminViewCountController(flushUseCase, AdminAccessGuard(adminToken)),
            ).controllerAdvice(GlobalExceptionHandler())
            .build()
}

class AdminViewCountControllerTest :
    BehaviorSpec({

        Given("admin 토큰 미설정(개발 기본값) - POST /api/admin/view-counts/flush") {
            val fixture = AdminControllerFixture()
            coEvery { fixture.flushUseCase.flush() } returns
                FlushViewCountsResult(boards = 5, updatedRows = 5, failed = 0)

            When("헤더 없이 플러시를 요청하면") {
                Then("200 OK와 플러시 결과 요약을 반환한다(무인증 통과)") {
                    fixture.client
                        .post()
                        .uri("/api/admin/view-counts/flush")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Success")
                        .jsonPath("$.result.boards")
                        .isEqualTo(5)
                        .jsonPath("$.result.updatedRows")
                        .isEqualTo(5)

                    coVerify { fixture.flushUseCase.flush() }
                }
            }
        }

        Given("admin 토큰 설정 - POST /api/admin/view-counts/flush") {
            val fixture = AdminControllerFixture(adminToken = "s3cret")
            coEvery { fixture.flushUseCase.flush() } returns
                FlushViewCountsResult(boards = 1, updatedRows = 1, failed = 0)

            When("X-Admin-Token 헤더 없이 요청하면") {
                Then("401 UNAUTHORIZED를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/admin/view-counts/flush")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("UNAUTHORIZED")
                }
            }

            When("올바른 X-Admin-Token 헤더로 요청하면") {
                Then("200 OK로 플러시가 수행된다") {
                    fixture.client
                        .post()
                        .uri("/api/admin/view-counts/flush")
                        .header("X-Admin-Token", "s3cret")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.result.boards")
                        .isEqualTo(1)
                }
            }
        }
    })
