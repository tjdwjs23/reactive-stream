package demo.search.adapter.`in`.web

import demo.search.application.port.`in`.BoardPage
import demo.search.application.port.`in`.CreateBoardUseCase
import demo.search.application.port.`in`.DeleteBoardUseCase
import demo.search.application.port.`in`.GetBoardUseCase
import demo.search.application.port.`in`.UpdateBoardUseCase
import demo.search.domain.exception.BoardNotFoundException
import demo.search.domain.model.Board
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

private class ControllerFixture {
    val createBoardUseCase = mockk<CreateBoardUseCase>()
    val getBoardUseCase = mockk<GetBoardUseCase>()
    val updateBoardUseCase = mockk<UpdateBoardUseCase>()
    val deleteBoardUseCase = mockk<DeleteBoardUseCase>()

    // 인증 사용자 id 제공자. standalone MockMvc는 보안 필터를 거치지 않으므로,
    // 여기서 목으로 고정 id를 돌려줍니다(실제 인가는 SecurityIntegrationTest가 검증).
    val authenticatedUserProvider =
        mockk<AuthenticatedUserProvider> {
            every { currentUserId() } returns 7L
            every { current() } returns AuthenticatedUser(id = 7L, isAdmin = false)
        }

    // MVC 컨트롤러(블로킹 핸들러)를 standalone MockMvc로 검증합니다(전 컨텍스트/서버 없이 컨트롤러 슬라이스만).
    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                BoardController(
                    createBoardUseCase,
                    getBoardUseCase,
                    updateBoardUseCase,
                    deleteBoardUseCase,
                    BoardWebMapper(),
                    authenticatedUserProvider,
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
            .build()
}

class BoardControllerTest :
    BehaviorSpec({

        val sampleBoard =
            Board(
                id = 1L,
                title = "테스트 제목",
                content = "테스트 내용입니다.",
                createdAt = LocalDateTime.now(),
            )

        Given("유효한 요청 Body가 주어졌을 때 - POST /api/boards") {
            val fixture = ControllerFixture()
            every { fixture.createBoardUseCase.createBoard(any()) } returns sampleBoard

            When("POST 요청을 보내면") {
                Then("201 Created와 Location 헤더, 생성된 Board를 통일 포맷으로 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/boards")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"테스트 제목","content":"테스트 내용입니다."}"""),
                        ).andExpect(status().isCreated)
                        .andExpect(header().string("Location", "/api/boards/1"))
                        .andExpect(jsonPath("$.code").value(201))
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.id").value(1))
                        .andExpect(jsonPath("$.result.title").value("테스트 제목"))
                        .andExpect(jsonPath("$.result.content").value("테스트 내용입니다."))
                }
            }
        }

        Given("존재하는 ID가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getBoard(1L) } returns sampleBoard

            When("GET 요청을 보내면") {
                Then("200 OK와 해당 Board를 통일 포맷으로 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/1"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.id").value(1))
                        .andExpect(jsonPath("$.result.title").value("테스트 제목"))
                        .andExpect(jsonPath("$.result.content").value("테스트 내용입니다."))
                }
            }
        }

        Given("존재하지 않는 ID가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getBoard(999L) } throws BoardNotFoundException(999L)

            When("GET 요청을 보내면") {
                Then("404 Not Found와 에러 코드/동적 메시지를 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/999"))
                        .andExpect(status().isNotFound)
                        .andExpect(jsonPath("$.code").value(404))
                        .andExpect(jsonPath("$.status").value("Failure"))
                        .andExpect(jsonPath("$.result.code").value("BOARD_NOT_FOUND"))
                        .andExpect(jsonPath("$.result.statusCode").value(404))
                        .andExpect(jsonPath("$.message").value("Board not found with id: 999"))
                }
            }
        }

        Given("숫자가 아닌 잘못된 형식의 id가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()

            When("GET 요청을 보내면") {
                Then("400 Bad Request와 에러 코드를 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/abc"))
                        .andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.status").value("Failure"))
                        .andExpect(jsonPath("$.result.code").value("INVALID_PARAMETER"))
                }
            }
        }

        Given("다음 페이지가 있는 Board 목록이 존재할 때 - GET /api/boards") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getBoards(any()) } returns
                BoardPage(
                    items =
                        listOf(
                            sampleBoard,
                            Board(id = 2L, title = "두 번째 제목", content = "두 번째 내용", createdAt = LocalDateTime.now()),
                        ),
                    nextCursor = 2L,
                    hasNext = true,
                )

            When("GET 요청을 보내면") {
                Then("200 OK와 페이지(items/nextCursor/hasNext)를 통일 포맷으로 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards?size=2"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.items.length()").value(2))
                        .andExpect(jsonPath("$.result.items[0].id").value(1))
                        .andExpect(jsonPath("$.result.items[1].id").value(2))
                        .andExpect(jsonPath("$.result.hasNext").value(true))
                        .andExpect(jsonPath("$.result.nextCursor").value(2))
                }
            }
        }

        Given("Board가 하나도 없을 때 - GET /api/boards") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getBoards(any()) } returns
                BoardPage(items = emptyList(), nextCursor = null, hasNext = false)

            When("GET 요청을 보내면") {
                Then("200 OK와 빈 페이지를 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.result.items.length()").value(0))
                        .andExpect(jsonPath("$.result.hasNext").value(false))
                }
            }
        }

        Given("존재하는 Board와 유효한 요청이 주어졌을 때 - PUT /api/boards/{id}") {
            val fixture = ControllerFixture()
            val updatedBoard = sampleBoard.copy(title = "수정된 제목", content = "수정된 내용")
            every { fixture.updateBoardUseCase.updateBoard(any()) } returns updatedBoard

            When("PUT 요청을 보내면") {
                Then("200 OK와 수정된 Board를 통일 포맷으로 반환한다") {
                    fixture.mockMvc
                        .perform(
                            put("/api/boards/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"수정된 제목","content":"수정된 내용"}"""),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.title").value("수정된 제목"))
                        .andExpect(jsonPath("$.result.content").value("수정된 내용"))
                }
            }
        }

        Given("유효하지 않은 요청 Body가 주어졌을 때 - POST /api/boards") {
            val fixture = ControllerFixture()

            When("내용이 10자 미만인 요청을 보내면") {
                Then("400 Bad Request와 에러 코드를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/boards")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"제목","content":"짧음"}"""),
                        ).andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.status").value("Failure"))
                        .andExpect(jsonPath("$.result.code").value("VALIDATION_ERROR"))
                }
            }

            When("Body 자체가 비어 있는 요청을 보내면") {
                Then("400 Bad Request와 에러 코드를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/boards")
                                .contentType(MediaType.APPLICATION_JSON),
                        ).andExpect(status().isBadRequest)
                        .andExpect(jsonPath("$.status").value("Failure"))
                        .andExpect(jsonPath("$.result.code").value("INVALID_REQUEST_BODY"))
                }
            }
        }

        Given("유효한 ID가 주어졌을 때 - DELETE /api/boards/{id}") {
            val fixture = ControllerFixture()
            val id = 1L
            every { fixture.deleteBoardUseCase.deleteBoard(any()) } returns Unit

            When("DELETE 요청을 보내면") {
                Then("204 No Content를 반환하고, 인증 요청자 정보를 담은 커맨드로 삭제를 위임한다") {
                    fixture.mockMvc
                        .perform(delete("/api/boards/$id"))
                        .andExpect(status().isNoContent)

                    verify {
                        fixture.deleteBoardUseCase.deleteBoard(match { it.id == id && it.requesterId == 7L })
                    }
                }
            }
        }
    })
