package demo.board.adapter.`in`.web

import com.fasterxml.jackson.databind.ObjectMapper
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

// 실제 애플리케이션 컨텍스트 + 보안 필터 체인을 그대로 띄워 인증/인가를 종단 간(E2E) 검증합니다.
// - 읽기는 공개, 쓰기는 인증, admin은 ROLE_ADMIN
// - 자체 발급 JWT로 signup→login→Bearer 흐름
// 리액티브 @SpringBootTest는 WebTestClient 빈을 자동 등록하지 않으므로 OpenApiDocsTest처럼 서버 포트로 직접 만듭니다.
// admin 부트스트랩을 켜기 위해 board.security.admin.password를 주입합니다(기본 username=admin).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest(
    @Value("\${local.server.port}") private val port: Int,
) : BehaviorSpec({

        val client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
        val objectMapper = ObjectMapper()

        // 응답 봉투(result)에서 특정 필드를 문자열로 뽑는 헬퍼.
        fun WebTestClient.ResponseSpec.resultField(name: String): String {
            val body = expectBody().returnResult().responseBody!!
            return objectMapper
                .readTree(body)
                .path("result")
                .path(name)
                .asText()
        }

        fun signUp(
            username: String,
            password: String,
        ): Long =
            client
                .post()
                .uri("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"username":"$username","password":"$password"}""")
                .exchange()
                .expectStatus()
                .isCreated
                .resultField("id")
                .toLong()

        fun login(
            username: String,
            password: String,
        ): String =
            client
                .post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"username":"$username","password":"$password"}""")
                .exchange()
                .expectStatus()
                .isOk
                .resultField("accessToken")

        Given("인증 없이 읽기/쓰기를 시도할 때") {
            When("GET /api/boards (목록)을 토큰 없이 호출하면") {
                Then("200 OK로 공개 조회된다") {
                    client
                        .get()
                        .uri("/api/boards")
                        .exchange()
                        .expectStatus()
                        .isOk
                }
            }

            When("POST /api/boards를 토큰 없이 호출하면") {
                Then("401 Unauthorized다") {
                    client
                        .post()
                        .uri("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"title":"제목","content":"10자 이상의 유효한 내용입니다."}""")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized
                }
            }
        }

        Given("가입/로그인한 일반 사용자가 있을 때") {
            val userId = signUp("sec-user", "password123")
            val userToken = login("sec-user", "password123")

            When("Bearer 토큰으로 게시글을 생성하면") {
                Then("201 Created이고 작성자 id가 내 id로 기록된다") {
                    val authorId =
                        client
                            .post()
                            .uri("/api/boards")
                            .header("Authorization", "Bearer $userToken")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("""{"title":"보안 테스트","content":"10자 이상의 유효한 내용입니다."}""")
                            .exchange()
                            .expectStatus()
                            .isCreated
                            .resultField("authorId")
                    authorId shouldBe userId.toString()
                }
            }

            When("일반 사용자 토큰으로 admin 플러시를 호출하면") {
                Then("403 Forbidden이다(ROLE_ADMIN 아님)") {
                    client
                        .post()
                        .uri("/api/admin/view-counts/flush")
                        .header("Authorization", "Bearer $userToken")
                        .exchange()
                        .expectStatus()
                        .isForbidden
                }
            }
        }

        Given("부트스트랩된 관리자가 있을 때") {
            val adminToken = login("admin", "admin-pass-123")

            When("관리자 토큰으로 admin 플러시를 호출하면") {
                Then("200 OK로 수행된다") {
                    client
                        .post()
                        .uri("/api/admin/view-counts/flush")
                        .header("Authorization", "Bearer $adminToken")
                        .exchange()
                        .expectStatus()
                        .isOk
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            TestContainers.registerAll(registry)
            // admin 부트스트랩 활성화(기동 시 ROLE_ADMIN 계정 생성).
            registry.add("board.security.admin.password") { "admin-pass-123" }
        }
    }
}
