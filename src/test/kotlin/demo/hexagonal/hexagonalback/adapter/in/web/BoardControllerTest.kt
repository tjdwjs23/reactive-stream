package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.DeleteBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.GetBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardUseCase
import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.model.Board
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@ExtendWith(MockitoExtension::class)
@DisplayName("BoardController")
class BoardControllerTest {

    @Mock private lateinit var createBoardUseCase: CreateBoardUseCase
    @Mock private lateinit var getBoardUseCase: GetBoardUseCase
    @Mock private lateinit var updateBoardUseCase: UpdateBoardUseCase
    @Mock private lateinit var deleteBoardUseCase: DeleteBoardUseCase

    private lateinit var mockMvc: MockMvc

    private val sampleBoard = Board(
        id = 1L,
        title = "테스트 제목",
        content = "테스트 내용입니다."
    )

    @BeforeEach
    fun setUp() {
        val controller = BoardController(
            createBoardUseCase,
            getBoardUseCase,
            updateBoardUseCase,
            deleteBoardUseCase,
            BoardWebMapper()
        )
        // GlobalExceptionHandler를 명시적으로 등록해야 standaloneSetup에서 예외 처리가 동작합니다.
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Nested
    @DisplayName("POST /api/boards")
    inner class CreateBoard {

        @Nested
        @DisplayName("Given: 유효한 요청 Body가 주어졌을 때")
        inner class GivenValidRequest {

            @Test
            @DisplayName("When: POST 요청을 보내면 / Then: 201 Created와 Location 헤더, 생성된 Board를 반환한다")
            fun `when valid request then returns 201 with location and board`() {
                // given
                whenever(createBoardUseCase.createBoard(any())).thenReturn(sampleBoard)

                // when & then
                mockMvc.perform(
                    post("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"title":"테스트 제목","content":"테스트 내용입니다."}""")
                )
                    .andExpect(status().isCreated)
                    .andExpect(header().string("Location", "/api/boards/1"))
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("테스트 제목"))
                    .andExpect(jsonPath("$.content").value("테스트 내용입니다."))
            }
        }
    }

    @Nested
    @DisplayName("GET /api/boards/{id}")
    inner class GetBoard {

        @Nested
        @DisplayName("Given: 존재하는 ID가 주어졌을 때")
        inner class GivenExistingId {

            @Test
            @DisplayName("When: GET 요청을 보내면 / Then: 200 OK와 해당 Board를 반환한다")
            fun `when existing id then returns 200 with board`() {
                // given
                whenever(getBoardUseCase.getBoard(1L)).thenReturn(sampleBoard)

                // when & then
                mockMvc.perform(get("/api/boards/1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("테스트 제목"))
                    .andExpect(jsonPath("$.content").value("테스트 내용입니다."))
            }
        }

        @Nested
        @DisplayName("Given: 존재하지 않는 ID가 주어졌을 때")
        inner class GivenNonExistingId {

            @Test
            @DisplayName("When: GET 요청을 보내면 / Then: 404 Not Found를 반환한다")
            fun `when non-existing id then returns 404`() {
                // given
                whenever(getBoardUseCase.getBoard(999L)).thenThrow(BoardNotFoundException(999L))

                // when & then
                mockMvc.perform(get("/api/boards/999"))
                    .andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.message").value("Board not found with id: 999"))
            }
        }
    }

    @Nested
    @DisplayName("GET /api/boards")
    inner class GetAllBoards {

        @Nested
        @DisplayName("Given: Board 목록이 존재할 때")
        inner class GivenBoardsExist {

            @Test
            @DisplayName("When: GET 요청을 보내면 / Then: 200 OK와 Board 목록을 반환한다")
            fun `when boards exist then returns 200 with list`() {
                // given
                val boards = listOf(
                    sampleBoard,
                    Board(id = 2L, title = "두 번째 제목", content = "두 번째 내용")
                )
                whenever(getBoardUseCase.getAllBoards()).thenReturn(boards)

                // when & then
                mockMvc.perform(get("/api/boards"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[1].id").value(2))
            }
        }

        @Nested
        @DisplayName("Given: Board가 하나도 없을 때")
        inner class GivenNoBoardsExist {

            @Test
            @DisplayName("When: GET 요청을 보내면 / Then: 200 OK와 빈 배열을 반환한다")
            fun `when no boards then returns 200 with empty list`() {
                // given
                whenever(getBoardUseCase.getAllBoards()).thenReturn(emptyList())

                // when & then
                mockMvc.perform(get("/api/boards"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.length()").value(0))
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/boards/{id}")
    inner class UpdateBoard {

        @Nested
        @DisplayName("Given: 존재하는 Board와 유효한 요청이 주어졌을 때")
        inner class GivenExistingBoard {

            @Test
            @DisplayName("When: PUT 요청을 보내면 / Then: 200 OK와 수정된 Board를 반환한다")
            fun `when existing board then returns 200 with updated board`() {
                // given
                val updatedBoard = sampleBoard.copy(title = "수정된 제목", content = "수정된 내용")
                whenever(updateBoardUseCase.updateBoard(any())).thenReturn(updatedBoard)

                // when & then
                mockMvc.perform(
                    put("/api/boards/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"title":"수정된 제목","content":"수정된 내용"}""")
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.title").value("수정된 제목"))
                    .andExpect(jsonPath("$.content").value("수정된 내용"))
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/boards/{id}")
    inner class DeleteBoard {

        @Nested
        @DisplayName("Given: 유효한 ID가 주어졌을 때")
        inner class GivenValidId {

            @Test
            @DisplayName("When: DELETE 요청을 보내면 / Then: 204 No Content를 반환한다")
            fun `when valid id then returns 204`() {
                // given
                val id = 1L

                // when & then
                mockMvc.perform(delete("/api/boards/$id"))
                    .andExpect(status().isNoContent)

                verify(deleteBoardUseCase).deleteBoard(id)
            }
        }
    }
}
