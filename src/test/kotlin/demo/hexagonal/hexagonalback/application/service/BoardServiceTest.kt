package demo.hexagonal.hexagonalback.application.service

import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardCommand
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardCommand
import demo.hexagonal.hexagonalback.application.port.out.BoardRepositoryPort
import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.model.Board
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@DisplayName("BoardService")
class BoardServiceTest {

    private val boardRepositoryPort: BoardRepositoryPort = mock()
    private val boardService = BoardService(boardRepositoryPort)

    @Nested
    @DisplayName("createBoard()")
    inner class CreateBoard {

        @Nested
        @DisplayName("Given: 유효한 CreateBoardCommand가 주어졌을 때")
        inner class GivenValidCommand {

            @Test
            @DisplayName("When: createBoard를 호출하면 / Then: 저장 후 Board를 반환한다")
            fun `when createBoard called then saves and returns board`() {
                // given
                val command = CreateBoardCommand(title = "제목", content = "10자 이상의 유효한 내용입니다.")
                val savedBoard = Board(id = 1L, title = "제목", content = "10자 이상의 유효한 내용입니다.")
                whenever(boardRepositoryPort.save(any())).thenReturn(savedBoard)

                // when
                val result = boardService.createBoard(command)

                // then
                assertThat(result.id).isEqualTo(1L)
                assertThat(result.title).isEqualTo("제목")
                verify(boardRepositoryPort).save(any())
            }
        }
    }

    @Nested
    @DisplayName("getBoard()")
    inner class GetBoard {

        @Nested
        @DisplayName("Given: 존재하는 ID가 주어졌을 때")
        inner class GivenExistingId {

            @Test
            @DisplayName("When: getBoard를 호출하면 / Then: 해당 Board를 반환한다")
            fun `when getBoard called with existing id then returns board`() {
                // given
                val board = Board(id = 1L, title = "제목", content = "내용")
                whenever(boardRepositoryPort.findById(1L)).thenReturn(board)

                // when
                val result = boardService.getBoard(1L)

                // then
                assertThat(result.id).isEqualTo(1L)
                assertThat(result.title).isEqualTo("제목")
            }
        }

        @Nested
        @DisplayName("Given: 존재하지 않는 ID가 주어졌을 때")
        inner class GivenNonExistingId {

            @Test
            @DisplayName("When: getBoard를 호출하면 / Then: BoardNotFoundException을 던진다")
            fun `when getBoard called with non-existing id then throws BoardNotFoundException`() {
                // given
                whenever(boardRepositoryPort.findById(999L)).thenReturn(null)

                // when & then
                assertThrows<BoardNotFoundException> {
                    boardService.getBoard(999L)
                }
            }
        }
    }

    @Nested
    @DisplayName("getAllBoards()")
    inner class GetAllBoards {

        @Nested
        @DisplayName("Given: Board 목록이 존재할 때")
        inner class GivenBoardsExist {

            @Test
            @DisplayName("When: getAllBoards를 호출하면 / Then: 전체 Board 목록을 반환한다")
            fun `when getAllBoards called then returns all boards`() {
                // given
                val boards = listOf(
                    Board(id = 1L, title = "제목1", content = "내용1"),
                    Board(id = 2L, title = "제목2", content = "내용2")
                )
                whenever(boardRepositoryPort.findAll()).thenReturn(boards)

                // when
                val result = boardService.getAllBoards()

                // then
                assertThat(result).hasSize(2)
                assertThat(result.map { it.id }).containsExactly(1L, 2L)
            }
        }

        @Nested
        @DisplayName("Given: Board가 하나도 없을 때")
        inner class GivenNoBoardsExist {

            @Test
            @DisplayName("When: getAllBoards를 호출하면 / Then: 빈 목록을 반환한다")
            fun `when getAllBoards called with no boards then returns empty list`() {
                // given
                whenever(boardRepositoryPort.findAll()).thenReturn(emptyList())

                // when
                val result = boardService.getAllBoards()

                // then
                assertThat(result).isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("updateBoard()")
    inner class UpdateBoard {

        @Nested
        @DisplayName("Given: 존재하는 Board와 유효한 Command가 주어졌을 때")
        inner class GivenExistingBoardAndValidCommand {

            @Test
            @DisplayName("When: updateBoard를 호출하면 / Then: 변경된 Board를 저장하고 반환한다")
            fun `when updateBoard called with existing board then saves and returns updated board`() {
                // given
                val existingBoard = Board(id = 1L, title = "원래 제목", content = "원래 내용", createdAt = LocalDateTime.now())
                val command = UpdateBoardCommand(id = 1L, title = "새 제목", content = "새 내용")
                val updatedBoard = existingBoard.update(command.title, command.content)
                whenever(boardRepositoryPort.findById(1L)).thenReturn(existingBoard)
                whenever(boardRepositoryPort.save(any())).thenReturn(updatedBoard)

                // when
                val result = boardService.updateBoard(command)

                // then
                assertThat(result.title).isEqualTo("새 제목")
                assertThat(result.content).isEqualTo("새 내용")
                verify(boardRepositoryPort).save(any())
            }
        }

        @Nested
        @DisplayName("Given: 존재하지 않는 ID의 Command가 주어졌을 때")
        inner class GivenNonExistingId {

            @Test
            @DisplayName("When: updateBoard를 호출하면 / Then: BoardNotFoundException을 던지고 저장하지 않는다")
            fun `when updateBoard called with non-existing id then throws and does not save`() {
                // given
                val command = UpdateBoardCommand(id = 999L, title = "새 제목", content = "새 내용")
                whenever(boardRepositoryPort.findById(999L)).thenReturn(null)

                // when & then
                assertThrows<BoardNotFoundException> {
                    boardService.updateBoard(command)
                }
                verify(boardRepositoryPort, never()).save(any())
            }
        }
    }

    @Nested
    @DisplayName("deleteBoard()")
    inner class DeleteBoard {

        @Nested
        @DisplayName("Given: 유효한 ID가 주어졌을 때")
        inner class GivenValidId {

            @Test
            @DisplayName("When: deleteBoard를 호출하면 / Then: Repository의 deleteById를 호출한다")
            fun `when deleteBoard called then delegates to repository`() {
                // given
                val id = 1L

                // when
                boardService.deleteBoard(id)

                // then
                verify(boardRepositoryPort).deleteById(id)
            }
        }
    }
}
