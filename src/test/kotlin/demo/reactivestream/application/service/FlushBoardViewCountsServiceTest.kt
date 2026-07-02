package demo.reactivestream.application.service

import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.application.port.out.BoardViewCountPort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

private class FlushFixture {
    val boardViewCountPort = mockk<BoardViewCountPort>()
    val boardRepositoryPort = mockk<BoardRepositoryPort>()
    val service = FlushBoardViewCountsService(boardViewCountPort, boardRepositoryPort)
}

class FlushBoardViewCountsServiceTest :
    BehaviorSpec({

        Given("Redis에 여러 게시글의 조회 델타가 쌓여 있을 때") {
            val fixture = FlushFixture()
            coEvery { fixture.boardViewCountPort.drainPendingDeltas() } returns mapOf(1L to 3L, 2L to 5L)
            coEvery { fixture.boardRepositoryPort.addViewCount(1L, 3L) } returns 1
            coEvery { fixture.boardRepositoryPort.addViewCount(2L, 5L) } returns 1

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("게시글별로 view_count에 델타를 더하고 결과를 집계한다") {
                    result.boards shouldBe 2
                    result.updatedRows shouldBe 2
                    result.failed shouldBe 0
                    coVerify { fixture.boardRepositoryPort.addViewCount(1L, 3L) }
                    coVerify { fixture.boardRepositoryPort.addViewCount(2L, 5L) }
                }
            }
        }

        Given("쌓인 델타가 없을 때") {
            val fixture = FlushFixture()
            coEvery { fixture.boardViewCountPort.drainPendingDeltas() } returns emptyMap()

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("DB를 건드리지 않고 빈 결과를 반환한다") {
                    result.boards shouldBe 0
                    result.updatedRows shouldBe 0
                    result.failed shouldBe 0
                    coVerify(exactly = 0) { fixture.boardRepositoryPort.addViewCount(any(), any()) }
                }
            }
        }

        Given("일부 게시글의 DB 반영이 실패할 때") {
            val fixture = FlushFixture()
            coEvery { fixture.boardViewCountPort.drainPendingDeltas() } returns mapOf(1L to 3L, 2L to 5L)
            coEvery { fixture.boardRepositoryPort.addViewCount(1L, 3L) } returns 1
            coEvery { fixture.boardRepositoryPort.addViewCount(2L, 5L) } throws RuntimeException("db down")

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("실패 건은 건너뛰고 나머지는 반영하며 failed로 집계한다") {
                    result.boards shouldBe 2
                    result.updatedRows shouldBe 1
                    result.failed shouldBe 1
                }
            }
        }
    })
