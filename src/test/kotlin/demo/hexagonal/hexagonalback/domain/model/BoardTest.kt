package demo.hexagonal.hexagonalback.domain.model

import demo.hexagonal.hexagonalback.domain.exception.BoardValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class BoardTest :
    BehaviorSpec({

        Given("유효한 Board가 존재할 때") {
            val board =
                Board(
                    id = 1L,
                    title = "원래 제목",
                    content = "원래 내용",
                )

            When("유효한 제목과 내용으로 업데이트하면") {
                val updated = board.update("새 제목", "새 내용")

                Then("변경된 필드를 가진 새 Board를 반환한다") {
                    updated.title shouldBe "새 제목"
                    updated.content shouldBe "새 내용"
                }

                Then("원본 Board는 불변 상태를 유지한다") {
                    board.title shouldBe "원래 제목"
                    board.content shouldBe "원래 내용"
                }

                Then("id와 createdAt은 보존된다") {
                    updated.id shouldBe board.id
                    updated.createdAt shouldBe board.createdAt
                }
            }
        }

        Given("유효하지 않은 입력값이 주어졌을 때") {
            val board = Board(id = 1L, title = "제목", content = "내용")

            When("빈 제목으로 업데이트하면") {
                Then("BoardValidationException을 던진다") {
                    shouldThrow<BoardValidationException> {
                        board.update("", "새 내용")
                    }
                }
            }

            When("공백만 있는 제목으로 업데이트하면") {
                Then("BoardValidationException을 던진다") {
                    shouldThrow<BoardValidationException> {
                        board.update("   ", "새 내용")
                    }
                }
            }
        }
    })
