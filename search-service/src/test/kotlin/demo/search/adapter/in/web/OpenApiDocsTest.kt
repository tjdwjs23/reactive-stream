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

// springdoc-openapi(Spring MVC)가 실제 컨텍스트에서 OpenAPI 문서를 생성/서빙하는지 MockMvc로 검증합니다.
@SpringBootTest
class OpenApiDocsTest(
    context: WebApplicationContext,
) : BehaviorSpec({

        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

        Given("springdoc-openapi가 구성되어 있을 때") {
            When("GET /v3/api-docs") {
                Then("OpenAPI 문서에 Board 엔드포인트가 포함된다") {
                    mockMvc
                        .perform(get("/v3/api-docs"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.info.title").value("Search Platform API"))
                        .andExpect(jsonPath("$.paths['/api/boards']").exists())
                        .andExpect(jsonPath("$.paths['/api/boards/{id}']").exists())
                }
            }

            When("GET /swagger-ui.html") {
                Then("Swagger UI로 리다이렉트된다(3xx)") {
                    mockMvc
                        .perform(get("/swagger-ui.html"))
                        .andExpect(status().is3xxRedirection)
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
