package demo.board.application.port.`in`

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
                        CreateBoardCommand(title = title, content = content)
                    }
                }

                Then("입력값이 그대로 저장된다") {
                    val command = CreateBoardCommand(title = title, content = content)

                    command.title shouldBe title
                    command.content shouldBe content
                }
            }
        }

        Given("유효하지 않은 제목이 주어졌을 때") {
            When("빈 제목으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "", content = "10자 이상의 유효한 내용입니다.")
                    }
                }
            }

            When("공백만 있는 제목으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "   ", content = "10자 이상의 유효한 내용입니다.")
                    }
                }
            }

            When("255자를 초과하는 제목으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "가".repeat(256), content = "10자 이상의 유효한 내용입니다.")
                    }
                }
            }

            When("정확히 255자 제목으로 생성하면") {
                Then("예외 없이 생성된다") {
                    shouldNotThrowAny {
                        CreateBoardCommand(title = "가".repeat(255), content = "10자 이상의 유효한 내용입니다.")
                    }
                }
            }
        }

        Given("유효하지 않은 내용이 주어졌을 때") {
            When("9자 내용으로 생성하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        CreateBoardCommand(title = "유효한 제목", content = "9자미만내용")
                    }
                }
            }

            When("정확히 10자 내용으로 생성하면") {
                Then("예외 없이 생성된다") {
                    shouldNotThrowAny {
                        CreateBoardCommand(title = "유효한 제목", content = "1234567890")
                    }
                }
            }
        }
    })
