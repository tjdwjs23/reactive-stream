package demo.hexagonal.hexagonalback.application.port.`in`

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

@DisplayName("CreateBoardCommand 자가 검증")
class CreateBoardCommandTest {

    @Nested
    @DisplayName("Given: 유효한 입력값이 주어졌을 때")
    inner class GivenValidInput {

        @Test
        @DisplayName("When: Command를 생성하면 / Then: 예외 없이 생성된다")
        fun `when created with valid input then no exception`() {
            // when & then
            assertDoesNotThrow {
                CreateBoardCommand(title = "유효한 제목", content = "10자 이상의 유효한 내용입니다.")
            }
        }

        @Test
        @DisplayName("When: Command를 생성하면 / Then: 입력값이 그대로 저장된다")
        fun `when created with valid input then fields are stored`() {
            // given
            val title = "유효한 제목"
            val content = "10자 이상의 유효한 내용입니다."

            // when
            val command = CreateBoardCommand(title = title, content = content)

            // then
            assertThat(command.title).isEqualTo(title)
            assertThat(command.content).isEqualTo(content)
        }
    }

    @Nested
    @DisplayName("Given: 유효하지 않은 제목이 주어졌을 때")
    inner class GivenInvalidTitle {

        @Test
        @DisplayName("When: 빈 제목으로 생성하면 / Then: IllegalArgumentException을 던진다")
        fun `when created with blank title then throws`() {
            // when & then
            assertThrows<IllegalArgumentException> {
                CreateBoardCommand(title = "", content = "10자 이상의 유효한 내용입니다.")
            }
        }

        @Test
        @DisplayName("When: 공백만 있는 제목으로 생성하면 / Then: IllegalArgumentException을 던진다")
        fun `when created with whitespace only title then throws`() {
            // when & then
            assertThrows<IllegalArgumentException> {
                CreateBoardCommand(title = "   ", content = "10자 이상의 유효한 내용입니다.")
            }
        }
    }

    @Nested
    @DisplayName("Given: 유효하지 않은 내용이 주어졌을 때")
    inner class GivenInvalidContent {

        @Test
        @DisplayName("When: 9자 내용으로 생성하면 / Then: IllegalArgumentException을 던진다")
        fun `when created with content shorter than 10 chars then throws`() {
            // when & then
            assertThrows<IllegalArgumentException> {
                CreateBoardCommand(title = "유효한 제목", content = "9자미만내용")
            }
        }

        @Test
        @DisplayName("When: 정확히 10자 내용으로 생성하면 / Then: 예외 없이 생성된다")
        fun `when created with exactly 10 chars content then no exception`() {
            // when & then
            assertDoesNotThrow {
                CreateBoardCommand(title = "유효한 제목", content = "1234567890")
            }
        }
    }
}
