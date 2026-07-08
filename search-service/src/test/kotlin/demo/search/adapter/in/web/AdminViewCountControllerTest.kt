package demo.search.adapter.`in`.web

import demo.search.application.port.`in`.FlushBoardViewCountsUseCase
import demo.search.application.port.`in`.FlushViewCountsResult
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

// 접근 통제(ROLE_ADMIN)는 SecurityConfig(필터 체인)의 책임이라 SecurityIntegrationTest가 검증합니다.
// 여기서는 standalone MockMvc로 핸들러가 유즈케이스를 호출하고 결과를 통일 포맷으로 반환하는지만 봅니다.
private class AdminControllerFixture {
    val flushUseCase = mockk<FlushBoardViewCountsUseCase>()

    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminViewCountController(flushUseCase))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
}

class AdminViewCountControllerTest :
    BehaviorSpec({

        Given("조회수 플러시 - POST /api/admin/view-counts/flush") {
            val fixture = AdminControllerFixture()
            every { fixture.flushUseCase.flush() } returns
                FlushViewCountsResult(boards = 5, updatedRows = 5, failed = 0)

            When("플러시를 요청하면") {
                Then("200 OK와 플러시 결과 요약을 반환한다") {
                    fixture.mockMvc
                        .perform(post("/api/admin/view-counts/flush"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.boards").value(5))
                        .andExpect(jsonPath("$.result.updatedRows").value(5))

                    verify { fixture.flushUseCase.flush() }
                }
            }
        }
    })
