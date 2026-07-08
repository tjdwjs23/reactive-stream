package demo.search.adapter.`in`.web

import demo.search.support.TestContainers
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

// 전체 애플리케이션 컨텍스트 + 서블릿 스택을 MockMvc로 구동해 Actuator/Micrometer 구성을 검증합니다.
// Boot 4는 @AutoConfigureMockMvc/TestRestTemplate 자동구성을 기본 제공하지 않으므로, WebApplicationContext로
// MockMvc를 직접 결선합니다(springSecurity()로 실제 보안 필터 체인까지 태움 — 실서버 없이 전체 디스패처 검증).
@SpringBootTest
class ActuatorEndpointTest(
    context: WebApplicationContext,
) : BehaviorSpec({

        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

        Given("Actuator가 구성되어 있을 때") {
            When("GET /actuator/health") {
                Then("전체 UP과 db·redis·elasticsearch 헬스가 노출된다") {
                    mockMvc
                        .perform(get("/actuator/health"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("UP"))
                        .andExpect(jsonPath("$.components.db.status").value("UP"))
                        .andExpect(jsonPath("$.components.redis.status").value("UP"))
                        .andExpect(jsonPath("$.components.elasticsearch.status").value("UP"))
                }
            }

            When("GET /actuator/metrics") {
                Then("Micrometer 메트릭 목록이 노출된다(메트릭 저장/조회는 Mimir, 여기선 로컬 디버깅용)") {
                    mockMvc
                        .perform(get("/actuator/metrics"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.names").exists())
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
