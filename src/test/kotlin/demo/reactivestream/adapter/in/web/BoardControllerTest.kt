package demo.reactivestream.adapter.`in`.web

import demo.reactivestream.application.port.`in`.BoardPage
import demo.reactivestream.application.port.`in`.CreateBoardUseCase
import demo.reactivestream.application.port.`in`.DeleteBoardUseCase
import demo.reactivestream.application.port.`in`.GetBoardUseCase
import demo.reactivestream.application.port.`in`.UpdateBoardUseCase
import demo.reactivestream.domain.exception.BoardNotFoundException
import demo.reactivestream.domain.model.Board
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

private class ControllerFixture {
    val createBoardUseCase = mockk<CreateBoardUseCase>()
    val getBoardUseCase = mockk<GetBoardUseCase>()
    val updateBoardUseCase = mockk<UpdateBoardUseCase>()
    val deleteBoardUseCase = mockk<DeleteBoardUseCase>()

    // WebFlux 컨트롤러(suspend 핸들러)는 MockMvc가 아닌 WebTestClient로 검증합니다.
    val client: WebTestClient =
        WebTestClient
            .bindToController(
                BoardController(
                    createBoardUseCase,
                    getBoardUseCase,
                    updateBoardUseCase,
                    deleteBoardUseCase,
                    BoardWebMapper(),
                ),
            ).controllerAdvice(GlobalExceptionHandler())
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
            coEvery { fixture.createBoardUseCase.createBoard(any()) } returns sampleBoard

            When("POST 요청을 보내면") {
                Then("201 Created와 Location 헤더, 생성된 Board를 통일 포맷으로 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"title":"테스트 제목","content":"테스트 내용입니다."}""")
                        .exchange()
                        .expectStatus()
                        .isCreated
                        .expectHeader()
                        .valueEquals("Location", "/api/boards/1")
                        .expectBody()
                        .jsonPath("$.code")
                        .isEqualTo(201)
                        .jsonPath("$.status")
                        .isEqualTo("Success")
                        .jsonPath("$.result.id")
                        .isEqualTo(1)
                        .jsonPath("$.result.title")
                        .isEqualTo("테스트 제목")
                        .jsonPath("$.result.content")
                        .isEqualTo("테스트 내용입니다.")
                }
            }
        }

        Given("존재하는 ID가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()
            coEvery { fixture.getBoardUseCase.getBoard(1L) } returns sampleBoard

            When("GET 요청을 보내면") {
                Then("200 OK와 해당 Board를 통일 포맷으로 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards/1")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.code")
                        .isEqualTo(200)
                        .jsonPath("$.status")
                        .isEqualTo("Success")
                        .jsonPath("$.result.id")
                        .isEqualTo(1)
                        .jsonPath("$.result.title")
                        .isEqualTo("테스트 제목")
                        .jsonPath("$.result.content")
                        .isEqualTo("테스트 내용입니다.")
                }
            }
        }

        Given("존재하지 않는 ID가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()
            coEvery { fixture.getBoardUseCase.getBoard(999L) } throws BoardNotFoundException(999L)

            When("GET 요청을 보내면") {
                Then("404 Not Found와 에러 코드/동적 메시지를 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards/999")
                        .exchange()
                        .expectStatus()
                        .isNotFound
                        .expectBody()
                        .jsonPath("$.code")
                        .isEqualTo(404)
                        .jsonPath("$.status")
                        .isEqualTo("Failure")
                        .jsonPath("$.result.code")
                        .isEqualTo("BOARD_NOT_FOUND")
                        .jsonPath("$.result.statusCode")
                        .isEqualTo(404)
                        .jsonPath("$.message")
                        .isEqualTo("Board not found with id: 999")
                }
            }
        }

        Given("존재하지 않는 형식의 id가 주어졌을 때 - GET /api/boards/{id}") {
            val fixture = ControllerFixture()

            When("GET 요청을 보내면") {
                Then("400 Bad Request와 에러 코드를 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards/abc")
                        .exchange()
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Failure")
                        .jsonPath("$.result.code")
                        .isEqualTo("INVALID_PARAMETER")
                }
            }
        }

        Given("다음 페이지가 있는 Board 목록이 존재할 때 - GET /api/boards") {
            val fixture = ControllerFixture()
            coEvery { fixture.getBoardUseCase.getBoards(any()) } returns
                BoardPage(
                    items = listOf(sampleBoard, Board(id = 2L, title = "두 번째 제목", content = "두 번째 내용")),
                    nextCursor = 2L,
                    hasNext = true,
                )

            When("GET 요청을 보내면") {
                Then("200 OK와 페이지(items/nextCursor/hasNext)를 통일 포맷으로 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards?size=2")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Success")
                        .jsonPath("$.result.items.length()")
                        .isEqualTo(2)
                        .jsonPath("$.result.items[0].id")
                        .isEqualTo(1)
                        .jsonPath("$.result.items[1].id")
                        .isEqualTo(2)
                        .jsonPath("$.result.hasNext")
                        .isEqualTo(true)
                        .jsonPath("$.result.nextCursor")
                        .isEqualTo(2)
                }
            }
        }

        Given("Board가 하나도 없을 때 - GET /api/boards") {
            val fixture = ControllerFixture()
            coEvery { fixture.getBoardUseCase.getBoards(any()) } returns
                BoardPage(items = emptyList(), nextCursor = null, hasNext = false)

            When("GET 요청을 보내면") {
                Then("200 OK와 빈 페이지를 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.result.items.length()")
                        .isEqualTo(0)
                        .jsonPath("$.result.hasNext")
                        .isEqualTo(false)
                }
            }
        }

        Given("존재하는 Board와 유효한 요청이 주어졌을 때 - PUT /api/boards/{id}") {
            val fixture = ControllerFixture()
            val updatedBoard = sampleBoard.copy(title = "수정된 제목", content = "수정된 내용")
            coEvery { fixture.updateBoardUseCase.updateBoard(any()) } returns updatedBoard

            When("PUT 요청을 보내면") {
                Then("200 OK와 수정된 Board를 통일 포맷으로 반환한다") {
                    fixture.client
                        .put()
                        .uri("/api/boards/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"title":"수정된 제목","content":"수정된 내용"}""")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Success")
                        .jsonPath("$.result.title")
                        .isEqualTo("수정된 제목")
                        .jsonPath("$.result.content")
                        .isEqualTo("수정된 내용")
                }
            }
        }

        Given("유효하지 않은 요청 Body가 주어졌을 때 - POST /api/boards") {
            val fixture = ControllerFixture()

            When("내용이 10자 미만인 요청을 보내면") {
                Then("400 Bad Request와 에러 코드를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""{"title":"제목","content":"짧음"}""")
                        .exchange()
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Failure")
                        .jsonPath("$.result.code")
                        .isEqualTo("VALIDATION_ERROR")
                }
            }

            When("Body 자체가 비어 있는 요청을 보내면") {
                Then("400 Bad Request와 에러 코드를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Failure")
                        .jsonPath("$.result.code")
                        .isEqualTo("INVALID_REQUEST_BODY")
                }
            }
        }

        Given("유효한 ID가 주어졌을 때 - DELETE /api/boards/{id}") {
            val fixture = ControllerFixture()
            val id = 1L
            coEvery { fixture.deleteBoardUseCase.deleteBoard(id) } returns Unit

            When("DELETE 요청을 보내면") {
                Then("204 No Content를 반환한다") {
                    fixture.client
                        .delete()
                        .uri("/api/boards/$id")
                        .exchange()
                        .expectStatus()
                        .isNoContent

                    coVerify { fixture.deleteBoardUseCase.deleteBoard(id) }
                }
            }
        }
    })
