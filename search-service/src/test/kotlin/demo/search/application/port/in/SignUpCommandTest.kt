package demo.search.application.port.`in`

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SignUpCommandTest :
    BehaviorSpec({

        Given("유효한 가입 입력값이 주어졌을 때") {
            When("Command를 생성하면") {
                Then("예외 없이 생성되고 값이 보존된다") {
                    val command = SignUpCommand(username = "gildong", password = "password123")
                    command.username shouldBe "gildong"
                    command.password shouldBe "password123"
                }
            }
        }

        Given("유효하지 않은 username이 주어졌을 때") {
            When("3자 미만이면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        SignUpCommand(username = "ab", password = "password123")
                    }
                }
            }

            When("50자를 초과하면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        SignUpCommand(username = "a".repeat(51), password = "password123")
                    }
                }
            }
        }

        Given("유효하지 않은 password가 주어졌을 때") {
            When("8자 미만이면") {
                Then("IllegalArgumentException을 던진다") {
                    shouldThrow<IllegalArgumentException> {
                        SignUpCommand(username = "gildong", password = "short")
                    }
                }
            }

            When("정확히 8자이면") {
                Then("예외 없이 생성된다") {
                    shouldNotThrowAny {
                        SignUpCommand(username = "gildong", password = "12345678")
                    }
                }
            }
        }
    })
