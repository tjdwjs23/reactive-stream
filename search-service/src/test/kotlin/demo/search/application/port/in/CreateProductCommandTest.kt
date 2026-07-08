package demo.search.application.port.`in`

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

// CreateProductCommand의 자가 검증(require) — 유효하지 않은 입력은 생성 시점에 IllegalArgumentException(→400)으로 막습니다.
class CreateProductCommandTest :
    StringSpec({
        "유효한 값이면 생성된다" {
            val c = CreateProductCommand(name = "삼각김밥", price = 1200)
            c.name shouldBe "삼각김밥"
            c.price shouldBe 1200L
        }

        "이름이 공백이면 예외" {
            shouldThrow<IllegalArgumentException> { CreateProductCommand(name = "  ", price = 100) }
        }

        "이름이 255자를 넘으면 예외" {
            shouldThrow<IllegalArgumentException> { CreateProductCommand(name = "가".repeat(256), price = 100) }
        }

        "가격이 음수면 예외" {
            shouldThrow<IllegalArgumentException> { CreateProductCommand(name = "상품", price = -1) }
        }
    })
