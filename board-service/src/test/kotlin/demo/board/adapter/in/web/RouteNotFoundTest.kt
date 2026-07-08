package demo.board.adapter.`in`.web

import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// 매핑되지 않은 경로는 서블릿 디스패처에서 NoResourceFoundException(404, ResponseStatusException 하위)으로 처리됩니다.
// 전역 예외 핸들러가 이를 500으로 뭉개지 않고 404 상태를 보존해 통일 포맷으로 응답하는지 검증합니다.
@SpringBootTest
class RouteNotFoundTest(
    context: WebApplicationContext,
) : BehaviorSpec({

        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

        Given("매핑되지 않은 경로에 요청할 때") {
            When("GET /definitely/not/a/route") {
                Then("500이 아니라 404를 통일 실패 포맷으로 반환한다") {
                    mockMvc
                        .perform(get("/definitely/not/a/route"))
                        .andExpect(status().isNotFound)
                        .andExpect(jsonPath("$.status").value("Failure"))
                        .andExpect(jsonPath("$.code").value(404))
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = TestContainers.registerAll(registry)
    }
}
