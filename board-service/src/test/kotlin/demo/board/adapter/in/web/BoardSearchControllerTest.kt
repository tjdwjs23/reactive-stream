package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.ReindexBoardsUseCase
import demo.board.application.port.`in`.ReindexResult
import demo.board.application.port.`in`.SearchBoardUseCase
import demo.board.application.port.out.BoardSearchHit
import demo.board.domain.model.Board
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

// reindex의 접근 통제(ROLE_ADMIN)는 SecurityConfig가 담당하며 SecurityIntegrationTest가 검증합니다.
// 여기서는 standalone MockMvc로 검색/재색인 핸들러 동작만 봅니다.
private class SearchControllerFixture {
    val searchBoardUseCase = mockk<SearchBoardUseCase>()
    val reindexBoardsUseCase = mockk<ReindexBoardsUseCase>()

    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                BoardSearchController(
                    searchBoardUseCase,
                    reindexBoardsUseCase,
                    BoardWebMapper(),
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
            .build()
}

class BoardSearchControllerTest :
    BehaviorSpec({

        Given("검색어에 매칭되는 게시글이 있을 때 - GET /api/boards/search") {
            val fixture = SearchControllerFixture()
            val hits =
                listOf(
                    BoardSearchHit(
                        board =
                            Board(
                                id = 1L,
                                title = "카카오메일 공지",
                                content = "스팸 필터 개선",
                                createdAt = LocalDateTime.now(),
                            ),
                        score = 2.0,
                        highlightedTitle = "카카오<em>메일</em> 공지",
                        highlightedContent = null,
                    ),
                    BoardSearchHit(
                        board = Board(id = 2L, title = "주소록 안내", content = "메일과는 무관", createdAt = LocalDateTime.now()),
                        score = 1.0,
                        highlightedTitle = null,
                        highlightedContent = "<em>메일</em>과는 무관",
                    ),
                )
            every { fixture.searchBoardUseCase.search(any()) } returns hits

            When("keyword로 검색하면") {
                Then("200 OK와 관련도순 검색 결과(점수·하이라이트 포함)를 통일 포맷으로 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/search?keyword=메일&size=5"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.keyword").value("메일"))
                        .andExpect(jsonPath("$.result.total").value(2))
                        .andExpect(jsonPath("$.result.items[0].board.id").value(1))
                        .andExpect(jsonPath("$.result.items[0].score").value(2.0))
                        .andExpect(jsonPath("$.result.items[0].highlightedTitle").value("카카오<em>메일</em> 공지"))
                        .andExpect(jsonPath("$.result.items[1].highlightedContent").value("<em>메일</em>과는 무관"))
                }
            }
        }

        Given("빈 keyword가 주어졌을 때 - GET /api/boards/search") {
            val fixture = SearchControllerFixture()

            When("keyword를 공백으로 요청하면") {
                Then("커맨드 자가검증(IllegalArgumentException)이 400 VALIDATION_ERROR로 매핑된다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/search?keyword="))
                        .andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.status").value("Failure"))
                        .andExpect(jsonPath("$.result.code").value("VALIDATION_ERROR"))
                }
            }
        }

        Given("size가 허용 범위를 벗어났을 때 - GET /api/boards/search") {
            val fixture = SearchControllerFixture()

            When("size=0으로 요청하면") {
                Then("400 VALIDATION_ERROR를 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/search?keyword=메일&size=0"))
                        .andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.result.code").value("VALIDATION_ERROR"))
                }
            }
        }

        Given("전체 재색인 - POST /api/boards/search/reindex") {
            val fixture = SearchControllerFixture()
            every { fixture.reindexBoardsUseCase.reindexAll() } returns
                ReindexResult(indexed = 42L, failed = 3L, swapped = false)

            When("재색인을 요청하면") {
                Then("200 OK와 색인/실패 건수 및 스왑 여부(failed>0이면 false)를 반환한다") {
                    fixture.mockMvc
                        .perform(post("/api/boards/search/reindex"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.reindexed").value(42))
                        .andExpect(jsonPath("$.result.failed").value(3))
                        .andExpect(jsonPath("$.result.swapped").value(false))

                    verify { fixture.reindexBoardsUseCase.reindexAll() }
                }
            }
        }
    })
