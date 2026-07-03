package demo.board.adapter.`in`.web

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.web.server.ResponseStatusException

class AdminAccessGuardTest :
    BehaviorSpec({

        Given("admin 토큰이 설정되지 않았을 때(빈 문자열)") {
            val guard = AdminAccessGuard("")

            When("어떤 토큰으로 검증하든") {
                Then("통과한다(개발 편의 — 무인증)") {
                    shouldNotThrowAny { guard.verify(null) }
                    shouldNotThrowAny { guard.verify("anything") }
                }
            }
        }

        Given("admin 토큰이 설정됐을 때") {
            val guard = AdminAccessGuard("s3cret")

            When("일치하는 토큰으로 검증하면") {
                Then("통과한다") {
                    shouldNotThrowAny { guard.verify("s3cret") }
                }
            }

            When("토큰이 없거나 틀리면") {
                Then("401 UNAUTHORIZED를 던진다") {
                    shouldThrow<ResponseStatusException> { guard.verify(null) }
                        .statusCode
                        .value() shouldBe 401
                    shouldThrow<ResponseStatusException> { guard.verify("wrong") }
                        .statusCode
                        .value() shouldBe 401
                }
            }
        }
    })
