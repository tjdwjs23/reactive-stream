package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.DeleteBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.GetBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardUseCase
import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.model.Board
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

private class ControllerFixture {
    val createBoardUseCase = mockk<CreateBoardUseCase>()
    val getBoardUseCase = mockk<GetBoardUseCase>()
    val updateBoardUseCase = mockk<UpdateBoardUseCase>()
    val deleteBoardUseCase = mockk<DeleteBoardUseCase>()

    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                BoardController(
                    createBoardUseCase,
                    getBoardUseCase,
                    updateBoardUseCase,
                    deleteBoardUseCase,
                    BoardWebMapper(),
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
            )

        Given("유효한 요청 Body가 주어졌을 때 - POST /api/boards") {
            val fixture = ControllerFixture()
            every { fixture.createBoardUseCase.createBoard(any()) } returns sampleBoard

            When("POST 요청을 보내면") {
                Then("201 Created와 Location 헤더, 생성된 Board를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/boards")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"테스트 제목","content":"테스트 내용입니다."}"""),
                        ).andExpect(status().isCreated)
                        .andExpect(header().string("Location", "/api/boards/1"))
                        .andExpect(jsonPath("$.id").value(1))
                        .andExpect(jsonPath("$.title").value("테스트 제목"))
                        .andExpect(jsonPath("$.content").value("테스트 내용입니다."))
                }
            }
        }

        Given("존재하는 ID가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getBoard(1L) } returns sampleBoard

            When("GET 요청을 보내면") {
                Then("200 OK와 해당 Board를 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/1"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.id").value(1))
                        .andExpect(jsonPath("$.title").value("테스트 제목"))
                        .andExpect(jsonPath("$.content").value("테스트 내용입니다."))
                }
            }
        }

        Given("존재하지 않는 ID가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getBoard(999L) } throws BoardNotFoundException(999L)

            When("GET 요청을 보내면") {
                Then("404 Not Found를 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards/999"))
                        .andExpect(status().isNotFound)
                        .andExpect(jsonPath("$.message").value("Board not found with id: 999"))
                }
            }
        }

        Given("Board 목록이 존재할 때 - GET /api/boards") {
            val fixture = ControllerFixture()
            val boards =
                listOf(
                    sampleBoard,
                    Board(id = 2L, title = "두 번째 제목", content = "두 번째 내용"),
                )
            every { fixture.getBoardUseCase.getAllBoards() } returns boards

            When("GET 요청을 보내면") {
                Then("200 OK와 Board 목록을 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.length()").value(2))
                        .andExpect(jsonPath("$[0].id").value(1))
                        .andExpect(jsonPath("$[1].id").value(2))
                }
            }
        }

        Given("Board가 하나도 없을 때 - GET /api/boards") {
            val fixture = ControllerFixture()
            every { fixture.getBoardUseCase.getAllBoards() } returns emptyList()

            When("GET 요청을 보내면") {
                Then("200 OK와 빈 배열을 반환한다") {
                    fixture.mockMvc
                        .perform(get("/api/boards"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.length()").value(0))
                }
            }
        }

        Given("존재하는 Board와 유효한 요청이 주어졌을 때 - PUT /api/boards/{id}") {
            val fixture = ControllerFixture()
            val updatedBoard = sampleBoard.copy(title = "수정된 제목", content = "수정된 내용")
            every { fixture.updateBoardUseCase.updateBoard(any()) } returns updatedBoard

            When("PUT 요청을 보내면") {
                Then("200 OK와 수정된 Board를 반환한다") {
                    fixture.mockMvc
                        .perform(
                            put("/api/boards/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"title":"수정된 제목","content":"수정된 내용"}"""),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.title").value("수정된 제목"))
                        .andExpect(jsonPath("$.content").value("수정된 내용"))
                }
            }
        }

        Given("유효한 ID가 주어졌을 때 - DELETE /api/boards/{id}") {
            val fixture = ControllerFixture()
            val id = 1L
            every { fixture.deleteBoardUseCase.deleteBoard(id) } returns Unit

            When("DELETE 요청을 보내면") {
                Then("204 No Content를 반환한다") {
                    fixture.mockMvc
                        .perform(delete("/api/boards/$id"))
                        .andExpect(status().isNoContent)

                    verify { fixture.deleteBoardUseCase.deleteBoard(id) }
                }
            }
        }
    })
