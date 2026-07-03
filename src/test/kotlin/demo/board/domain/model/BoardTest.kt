package demo.board.domain.model

import demo.board.domain.exception.BoardValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class BoardTest :
    BehaviorSpec({

        Given("유효한 Board가 존재할 때") {
            val board =
                Board(
                    id = 1L,
                    title = "원래 제목",
                    content = "원래 내용입니다.",
                    createdAt = LocalDateTime.now(),
                )

            When("유효한 제목과 내용으로 업데이트하면") {
                val updated = board.update("새 제목", "새 내용은 열 자 이상입니다")

                Then("변경된 필드를 가진 새 Board를 반환한다") {
                    updated.title shouldBe "새 제목"
                    updated.content shouldBe "새 내용은 열 자 이상입니다"
                }

                Then("원본 Board는 불변 상태를 유지한다") {
                    board.title shouldBe "원래 제목"
                    board.content shouldBe "원래 내용입니다."
                }

                Then("id와 createdAt은 보존된다") {
                    updated.id shouldBe board.id
                    updated.createdAt shouldBe board.createdAt
                }
            }
        }

        Given("유효하지 않은 입력값이 주어졌을 때") {
            val board = Board(id = 1L, title = "제목", content = "내용입니다열자", createdAt = LocalDateTime.now())

            When("빈 제목으로 업데이트하면") {
                Then("BoardValidationException을 던진다") {
                    shouldThrow<BoardValidationException> {
                        board.update("", "새 내용은 열 자 이상입니다")
                    }
                }
            }

            When("공백만 있는 제목으로 업데이트하면") {
                Then("BoardValidationException을 던진다") {
                    shouldThrow<BoardValidationException> {
                        board.update("   ", "새 내용은 열 자 이상입니다")
                    }
                }
            }

            When("255자를 초과하는 제목으로 업데이트하면") {
                Then("BoardValidationException을 던진다") {
                    shouldThrow<BoardValidationException> {
                        board.update("가".repeat(256), "새 내용은 열 자 이상입니다")
                    }
                }
            }

            When("10자 미만 내용으로 업데이트하면") {
                Then("BoardValidationException을 던진다(생성·수정 검증 대칭)") {
                    shouldThrow<BoardValidationException> {
                        board.update("새 제목", "짧은내용")
                    }
                }
            }
        }

        Given("보관 기간(retentionDays)이 365일일 때") {
            val now = LocalDateTime.of(2026, 7, 2, 0, 0)

            When("생성일이 365일보다 이전이면") {
                val board = Board(id = 1L, title = "제목", content = "내용", createdAt = now.minusDays(400))

                Then("아카이브 대상이다") {
                    board.isStale(now, 365) shouldBe true
                }
            }

            When("생성일이 365일 이내면") {
                val board = Board(id = 2L, title = "제목", content = "내용", createdAt = now.minusDays(10))

                Then("아카이브 대상이 아니다") {
                    board.isStale(now, 365) shouldBe false
                }
            }
        }
    })
