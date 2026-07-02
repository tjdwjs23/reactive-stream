package demo.reactivestream.adapter.`in`.web

import demo.reactivestream.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

// springdoc-openapi(WebFlux)가 실제 서버에서 OpenAPI 문서를 생성/서빙하는지 검증합니다.
// (ActuatorEndpointTest와 동일한 @SpringBootTest 설정이라 Spring 컨텍스트 캐시를 공유합니다.)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsTest(
    @Value("\${local.server.port}") private val port: Int,
) : BehaviorSpec({

        val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

        Given("springdoc-openapi가 구성되어 있을 때") {
            When("GET /v3/api-docs") {
                Then("OpenAPI 문서에 Board 엔드포인트가 포함된다") {
                    client
                        .get()
                        .uri("/v3/api-docs")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.info.title")
                        .isEqualTo("Reactive Stream Board API")
                        .jsonPath("$.paths['/api/boards']")
                        .exists()
                        .jsonPath("$.paths['/api/boards/{id}']")
                        .exists()
                }
            }

            When("GET /swagger-ui.html") {
                Then("Swagger UI로 리다이렉트된다(3xx)") {
                    client
                        .get()
                        .uri("/swagger-ui.html")
                        .exchange()
                        .expectStatus()
                        .is3xxRedirection
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
