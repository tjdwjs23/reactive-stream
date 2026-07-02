package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.support.PostgresTestContainer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

// 존재하지 않는 경로는 전체 서버에서 NoResourceFoundException(404)로 처리됩니다.
// 전역 예외 핸들러가 이를 500으로 뭉개지 않고 404 상태를 보존해 통일 포맷으로 응답하는지 검증합니다.
// (ActuatorEndpointTest와 동일한 @SpringBootTest 설정이라 Spring 컨텍스트 캐시를 공유합니다.)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteNotFoundTest(
    @Value("\${local.server.port}") private val port: Int,
) : BehaviorSpec({

        val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

        Given("매핑되지 않은 경로에 요청할 때") {
            When("GET /definitely/not/a/route") {
                Then("500이 아니라 404를 통일 실패 포맷으로 반환한다") {
                    client
                        .get()
                        .uri("/definitely/not/a/route")
                        .exchange()
                        .expectStatus()
                        .isNotFound
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Failure")
                        .jsonPath("$.code")
                        .isEqualTo(404)
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = PostgresTestContainer.registerProperties(registry)
    }
}
