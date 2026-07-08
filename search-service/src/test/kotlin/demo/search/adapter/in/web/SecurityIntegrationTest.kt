package demo.search.adapter.`in`.web

import com.jayway.jsonpath.JsonPath
import demo.search.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// 실제 애플리케이션 컨텍스트 + 보안 필터 체인을 그대로 태워 인증/인가를 종단 간(E2E) 검증합니다.
// - 읽기는 공개, 쓰기는 인증, admin은 ROLE_ADMIN
// - 자체 발급 JWT로 signup→login→Bearer 흐름
// Boot 4는 @AutoConfigureMockMvc를 기본 제공하지 않으므로 WebApplicationContext + springSecurity()로 직접 결선합니다
// (서블릿 보안 필터 체인을 MockMvc에 태움 — 실서버 불필요). admin 부트스트랩은 board.security.admin.password로 켭니다.
@SpringBootTest
class SecurityIntegrationTest(
    context: WebApplicationContext,
) : BehaviorSpec({

        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

        // 응답 봉투(result)에서 특정 필드를 문자열로 뽑는 헬퍼(JsonPath).
        fun MvcResult.resultField(name: String): String =
            JsonPath.read<Any>(response.contentAsString, "$.result.$name").toString()

        fun signUp(
            username: String,
            password: String,
        ): Long =
            mockMvc
                .perform(
                    post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"username":"$username","password":"$password"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .resultField("id")
                .toLong()

        fun login(
            username: String,
            password: String,
        ): String =
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"username":"$username","password":"$password"}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .resultField("accessToken")

        Given("인증 없이 읽기/쓰기를 시도할 때") {
            When("GET /api/boards (목록)을 토큰 없이 호출하면") {
                Then("200 OK로 공개 조회된다") {
                    mockMvc.perform(get("/api/boards")).andExpect(status().isOk)
                }
            }

            When("POST /api/boards를 토큰 없이 호출하면") {
                Then("401 Unauthorized다") {
                    mockMvc
                        .perform(
                            post("/api/boards")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"제목","content":"10자 이상의 유효한 내용입니다."}"""),
                        ).andExpect(status().isUnauthorized)
                }
            }
        }

        Given("가입/로그인한 일반 사용자가 있을 때") {
            val userId = signUp("sec-user", "password123")
            val userToken = login("sec-user", "password123")

            When("Bearer 토큰으로 게시글을 생성하면") {
                Then("201 Created이고 작성자 id가 내 id로 기록된다") {
                    val authorId =
                        mockMvc
                            .perform(
                                post("/api/boards")
                                    .header("Authorization", "Bearer $userToken")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""{"title":"보안 테스트","content":"10자 이상의 유효한 내용입니다."}"""),
                            ).andExpect(status().isCreated)
                            .andReturn()
                            .resultField("authorId")
                    authorId shouldBe userId.toString()
                }
            }

            When("일반 사용자 토큰으로 admin 플러시를 호출하면") {
                Then("403 Forbidden이다(ROLE_ADMIN 아님)") {
                    mockMvc
                        .perform(
                            post("/api/admin/view-counts/flush")
                                .header("Authorization", "Bearer $userToken"),
                        ).andExpect(status().isForbidden)
                }
            }

            When("일반 사용자 토큰으로 admin 아카이브를 호출하면") {
                Then("403 Forbidden이다(필터에서 거부 — 아카이브는 실행되지 않는다)") {
                    mockMvc
                        .perform(
                            post("/api/admin/boards/archive")
                                .header("Authorization", "Bearer $userToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"retentionDays":365}"""),
                        ).andExpect(status().isForbidden)
                }
            }
        }

        Given("부트스트랩된 관리자가 있을 때") {
            val adminToken = login("admin", "admin-pass-123")

            When("관리자 토큰으로 admin 플러시를 호출하면") {
                Then("200 OK로 수행된다") {
                    mockMvc
                        .perform(
                            post("/api/admin/view-counts/flush")
                                .header("Authorization", "Bearer $adminToken"),
                        ).andExpect(status().isOk)
                }
            }
        }

        Given("소유권 인가 - 한 사용자가 만든 게시글이 있을 때") {
            signUp("owner-1", "password123")
            val ownerToken = login("owner-1", "password123")
            signUp("intruder-1", "password123")
            val intruderToken = login("intruder-1", "password123")
            val adminToken = login("admin", "admin-pass-123")

            // 소유자가 게시글을 생성하고 그 id를 확보
            val boardId =
                mockMvc
                    .perform(
                        post("/api/boards")
                            .header("Authorization", "Bearer $ownerToken")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"title":"소유권 원본","content":"10자 이상의 유효한 내용입니다."}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .resultField("id")
                    .toLong()

            When("다른 사용자가 그 게시글을 수정하려 하면") {
                Then("403 Forbidden이고 에러 코드는 BOARD_ACCESS_DENIED다") {
                    mockMvc
                        .perform(
                            put("/api/boards/$boardId")
                                .header("Authorization", "Bearer $intruderToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"탈취 제목","content":"남의 글을 고치려는 시도입니다."}"""),
                        ).andExpect(status().isForbidden)
                        .andExpect(jsonPath("$.result.code").value("BOARD_ACCESS_DENIED"))
                }
            }

            When("다른 사용자가 그 게시글을 삭제하려 하면") {
                Then("403 Forbidden이다") {
                    mockMvc
                        .perform(
                            delete("/api/boards/$boardId")
                                .header("Authorization", "Bearer $intruderToken"),
                        ).andExpect(status().isForbidden)
                }
            }

            When("소유자 본인이 그 게시글을 수정하면") {
                Then("200 OK로 수정된다") {
                    mockMvc
                        .perform(
                            put("/api/boards/$boardId")
                                .header("Authorization", "Bearer $ownerToken")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"소유자 수정","content":"소유자가 직접 고친 내용입니다."}"""),
                        ).andExpect(status().isOk)
                }
            }

            When("관리자가 그 게시글을 삭제하면") {
                Then("소유자가 아니어도 204 No Content로 삭제된다") {
                    mockMvc
                        .perform(
                            delete("/api/boards/$boardId")
                                .header("Authorization", "Bearer $adminToken"),
                        ).andExpect(status().isNoContent)
                }
            }
        }

        Given("CORS - 허용 오리진에서 프리플라이트를 보낼 때") {
            When("OPTIONS 프리플라이트를 허용 오리진(http://localhost:3000)으로 보내면") {
                Then("Access-Control-Allow-Origin이 그 오리진으로 응답된다") {
                    mockMvc
                        .perform(
                            options("/api/boards")
                                .header("Origin", "http://localhost:3000")
                                .header("Access-Control-Request-Method", "POST"),
                        ).andExpect(status().isOk)
                        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                }
            }
        }

        Given("Refresh Token 흐름 - 로그인으로 리프레시 토큰을 받았을 때") {
            signUp("refresh-user", "password123")
            val refreshToken =
                mockMvc
                    .perform(
                        post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"username":"refresh-user","password":"password123"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .resultField("refreshToken")

            When("그 리프레시 토큰으로 /api/auth/refresh를 호출하면") {
                val rotated =
                    mockMvc
                        .perform(
                            post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"refreshToken":"$refreshToken"}"""),
                        ).andExpect(status().isOk)
                        .andReturn()
                        .resultField("refreshToken")

                Then("새 액세스+리프레시 토큰이 발급되고(회전) 이전 토큰은 재사용 시 401이다") {
                    // 회전됐으므로 새 리프레시 토큰은 이전과 다르다.
                    (rotated != refreshToken) shouldBe true
                    // 이미 회전(폐기)된 이전 토큰을 다시 쓰면 재사용 감지로 401.
                    mockMvc
                        .perform(
                            post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"refreshToken":"$refreshToken"}"""),
                        ).andExpect(status().isUnauthorized)
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
