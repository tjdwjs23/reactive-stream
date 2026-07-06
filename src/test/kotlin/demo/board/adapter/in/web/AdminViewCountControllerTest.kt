package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.FlushBoardViewCountsUseCase
import demo.board.application.port.`in`.FlushViewCountsResult
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.springframework.test.web.reactive.server.WebTestClient

// 접근 통제(ROLE_ADMIN)는 SecurityConfig(필터 체인)의 책임이라 SecurityIntegrationTest가 검증합니다.
// 여기서는 standalone WebTestClient로 핸들러가 유즈케이스를 호출하고 결과를 통일 포맷으로 반환하는지만 봅니다.
private class AdminControllerFixture {
    val flushUseCase = mockk<FlushBoardViewCountsUseCase>()

    val client: WebTestClient =
        WebTestClient
            .bindToController(AdminViewCountController(flushUseCase))
            .controllerAdvice(GlobalExceptionHandler())
            .build()
}

class AdminViewCountControllerTest :
    BehaviorSpec({

        Given("조회수 플러시 - POST /api/admin/view-counts/flush") {
            val fixture = AdminControllerFixture()
            coEvery { fixture.flushUseCase.flush() } returns
                FlushViewCountsResult(boards = 5, updatedRows = 5, failed = 0)

            When("플러시를 요청하면") {
                Then("200 OK와 플러시 결과 요약을 반환한다") {
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
    })
