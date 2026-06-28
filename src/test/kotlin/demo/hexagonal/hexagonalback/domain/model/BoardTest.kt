package demo.hexagonal.hexagonalback.domain.model

import demo.hexagonal.hexagonalback.domain.exception.BoardValidationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Board 도메인 모델")
class BoardTest {

    @Nested
    @DisplayName("update()")
    inner class Update {

        @Nested
        @DisplayName("Given: 유효한 Board가 존재할 때")
        inner class GivenValidBoard {

            private val board = Board(
                id = 1L,
                title = "원래 제목",
                content = "원래 내용"
            )

            @Test
            @DisplayName("When: 유효한 제목과 내용으로 업데이트하면 / Then: 변경된 필드를 가진 새 Board를 반환한다")
            fun `when updated with valid data then returns new board with updated fields`() {
                // when
                val updated = board.update("새 제목", "새 내용")

                // then
                assertThat(updated.title).isEqualTo("새 제목")
                assertThat(updated.content).isEqualTo("새 내용")
            }

            @Test
            @DisplayName("When: 업데이트하면 / Then: 원본 Board는 불변 상태를 유지한다")
            fun `when updated then original board remains unchanged`() {
                // when
                board.update("새 제목", "새 내용")

                // then - data class copy()는 새 인스턴스를 반환하므로 원본 불변
                assertThat(board.title).isEqualTo("원래 제목")
                assertThat(board.content).isEqualTo("원래 내용")
            }

            @Test
            @DisplayName("When: 업데이트하면 / Then: id와 createdAt은 보존된다")
            fun `when updated then id and createdAt are preserved`() {
                // when
                val updated = board.update("새 제목", "새 내용")

                // then
                assertThat(updated.id).isEqualTo(board.id)
                assertThat(updated.createdAt).isEqualTo(board.createdAt)
            }
        }

        @Nested
        @DisplayName("Given: 유효한 Board가 존재할 때")
        inner class GivenInvalidInput {

            private val board = Board(id = 1L, title = "제목", content = "내용")

            @Test
            @DisplayName("When: 빈 제목으로 업데이트하면 / Then: BoardValidationException을 던진다")
            fun `when updated with blank title then throws BoardValidationException`() {
                // when & then
                assertThrows<BoardValidationException> {
                    board.update("", "새 내용")
                }
            }

            @Test
            @DisplayName("When: 공백만 있는 제목으로 업데이트하면 / Then: BoardValidationException을 던진다")
            fun `when updated with whitespace only title then throws BoardValidationException`() {
                // when & then
                assertThrows<BoardValidationException> {
                    board.update("   ", "새 내용")
                }
            }
        }
    }
}
