package demo.search.application.port.`in`

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CreateBoardCommandTest :
    BehaviorSpec({

        Given("유효한 입력값이 주어졌을 때") {
            val title = "유효한 제목"
            val content = "10자 이상의 유효한 내용입니다."

            When("Command를 생성하면") {
                Then("예외 없이 생성된다") {
                    shouldNotThrowAny {
                        CreateBoardCommand(title = title, content = content, authorId = 1L)
                    }
                }

                Then("입력값이 그대로 저장된다") {
                    val command = CreateBoardCommand(title = title, content = content, authorId = 7L)

                    command.title shouldBe title
                    command.content shouldBe content
                    command.authorId shouldBe 7L
                }
            }
        }

        Given("유효하지 않은 작성자 id가 주어졌을 때") {
            When("authorId가 0 이하이면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "유효한 제목", content = "10자 이상의 유효한 내용입니다.", authorId = 0L)
                    }
                }
            }
        }

        Given("유효하지 않은 제목이 주어졌을 때") {
            When("빈 제목으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "", content = "10자 이상의 유효한 내용입니다.", authorId = 1L)
                    }
                }
            }

            When("공백만 있는 제목으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "   ", content = "10자 이상의 유효한 내용입니다.", authorId = 1L)
                    }
                }
            }

            When("255자를 초과하는 제목으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "가".repeat(256), content = "10자 이상의 유효한 내용입니다.", authorId = 1L)
                    }
                }
            }

            When("정확히 255자 제목으로 생성하면") {
                Then("예외 없이 생성된다") {
                    shouldNotThrowAny {
                        CreateBoardCommand(title = "가".repeat(255), content = "10자 이상의 유효한 내용입니다.", authorId = 1L)
                    }
                }
            }
        }

        Given("유효하지 않은 내용이 주어졌을 때") {
            When("10자 미만 내용으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "유효한 제목", content = "10자미만", authorId = 1L)
                    }
                }
            }

            When("정확히 10자 내용으로 생성하면") {
                Then("예외 없이 생성된다") {
                    shouldNotThrowAny {
                        CreateBoardCommand(title = "유효한 제목", content = "1234567890", authorId = 1L)
                    }
                }
            }
        }
    })
