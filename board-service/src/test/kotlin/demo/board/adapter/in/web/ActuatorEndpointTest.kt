package demo.board.adapter.`in`.web

import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

// 실제 Netty 서버를 띄우고 그 포트로 WebTestClient를 붙여 Actuator/Micrometer 구성을 검증합니다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointTest(
    @Value("\${local.server.port}") private val port: Int,
) : BehaviorSpec({

        val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

        Given("Actuator가 구성되어 있을 때") {
            When("GET /actuator/health") {
                Then("전체 UP과 r2dbc·redis·elasticsearch 헬스가 노출된다") {
                    client
                        .get()
                        .uri("/actuator/health")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("UP")
                        .jsonPath("$.components.r2dbc.status")
                        .isEqualTo("UP")
                        .jsonPath("$.components.redis.status")
                        .isEqualTo("UP")
                        .jsonPath("$.components.elasticsearch.status")
                        .isEqualTo("UP")
                }
            }

            When("GET /actuator/metrics") {
                Then("Micrometer 메트릭 목록이 노출된다(메트릭 저장/조회는 Mimir, 여기선 로컬 디버깅용)") {
                    client
                        .get()
                        .uri("/actuator/metrics")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.names")
                        .exists()
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
