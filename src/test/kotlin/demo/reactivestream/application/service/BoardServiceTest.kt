package demo.reactivestream.application.service

import demo.reactivestream.application.port.`in`.BoardPageQuery
import demo.reactivestream.application.port.`in`.CreateBoardCommand
import demo.reactivestream.application.port.`in`.UpdateBoardCommand
import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.application.port.out.BoardSearchPort
import demo.reactivestream.application.port.out.BoardViewCountPort
import demo.reactivestream.domain.exception.BoardNotFoundException
import demo.reactivestream.domain.model.Board
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.containExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import java.time.LocalDateTime

private class ServiceFixture {
    val boardRepositoryPort = mockk<BoardRepositoryPort>()
    val boardViewCountPort = mockk<BoardViewCountPort>()

    // 검색 색인은 쓰기 경로의 best-effort 부수효과이므로 relaxed 목으로 둡니다(index/deleteById는 무동작).
    val boardSearchPort = mockk<BoardSearchPort>(relaxed = true)
    val boardService = BoardService(boardRepositoryPort, boardViewCountPort, boardSearchPort)
}

class BoardServiceTest :
    BehaviorSpec({

        Given("유효한 CreateBoardCommand가 주어졌을 때") {
            val fixture = ServiceFixture()
            val command = CreateBoardCommand(title = "제목", content = "10자 이상의 유효한 내용입니다.")
            val savedBoard = Board(id = 1L, title = "제목", content = "10자 이상의 유효한 내용입니다.")
            coEvery { fixture.boardRepositoryPort.save(any()) } returns savedBoard

            When("createBoard를 호출하면") {
                val result = fixture.boardService.createBoard(command)

                Then("저장 후 Board를 반환한다") {
                    result.id shouldBe 1L
                    result.title shouldBe "제목"
                    coVerify { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("존재하는 ID가 주어졌을 때") {
            val fixture = ServiceFixture()
            val board = Board(id = 1L, title = "제목", content = "내용", viewCount = 10L)
            coEvery { fixture.boardRepositoryPort.findById(1L) } returns board
            // 조회 시 Redis 델타 +1, 아직 DB에 반영 안 된 누적 델타(3)를 반환한다고 가정
            coEvery { fixture.boardViewCountPort.increment(1L) } returns 3L

            When("getBoard를 호출하면") {
                val result = fixture.boardService.getBoard(1L)

                Then("조회수를 증가시키고, DB 누적값 + 미반영 델타를 조회수로 반환한다") {
                    result.id shouldBe 1L
                    result.title shouldBe "제목"
                    result.viewCount shouldBe 13L
                    coVerify { fixture.boardViewCountPort.increment(1L) }
                }
            }
        }

        Given("존재하지 않는 ID가 주어졌을 때") {
            val fixture = ServiceFixture()
            coEvery { fixture.boardRepositoryPort.findById(999L) } returns null

            When("getBoard를 호출하면") {
                Then("BoardNotFoundException을 던진다") {
                    shouldThrow<BoardNotFoundException> {
                        fixture.boardService.getBoard(999L)
                    }
                }
            }
        }

        Given("Redis(조회수 버퍼)가 장애일 때") {
            val fixture = ServiceFixture()
            val board = Board(id = 1L, title = "제목", content = "내용", viewCount = 42L)
            coEvery { fixture.boardRepositoryPort.findById(1L) } returns board
            coEvery { fixture.boardViewCountPort.increment(1L) } throws RuntimeException("redis down")

            When("getBoard를 호출하면") {
                val result = fixture.boardService.getBoard(1L)

                Then("조회는 실패하지 않고, 미반영 델타 없이 DB 누적값으로 응답한다") {
                    result.id shouldBe 1L
                    result.viewCount shouldBe 42L // 델타 0으로 강등(best-effort)
                    coVerify { fixture.boardViewCountPort.increment(1L) }
                }
            }
        }

        Given("다음 페이지가 있을 만큼 Board가 많을 때") {
            val fixture = ServiceFixture()
            // size=2 요청 → 서비스는 hasNext 판정을 위해 size+1(=3)건을 id 내림차순으로 조회한다
            val rows =
                listOf(
                    Board(id = 5L, title = "제목5", content = "내용5"),
                    Board(id = 4L, title = "제목4", content = "내용4"),
                    Board(id = 3L, title = "제목3", content = "내용3"),
                )
            every { fixture.boardRepositoryPort.findPage(null, 3) } returns rows.asFlow()

            When("getBoards(cursor=null, size=2)를 호출하면") {
                val page = fixture.boardService.getBoards(BoardPageQuery(cursor = null, size = 2))

                Then("size만큼만 담고 다음 페이지 커서(마지막 항목 id)를 준다") {
                    page.items.map { it.id } should containExactly(5L, 4L)
                    page.hasNext shouldBe true
                    page.nextCursor shouldBe 4L
                }
            }
        }

        Given("남은 Board가 size 이하일 때") {
            val fixture = ServiceFixture()
            val rows =
                listOf(
                    Board(id = 2L, title = "제목2", content = "내용2"),
                    Board(id = 1L, title = "제목1", content = "내용1"),
                )
            every { fixture.boardRepositoryPort.findPage(10L, 3) } returns rows.asFlow()

            When("getBoards(cursor=10, size=2)를 호출하면") {
                val page = fixture.boardService.getBoards(BoardPageQuery(cursor = 10L, size = 2))

                Then("전부 담고 다음 페이지가 없다") {
                    page.items shouldHaveSize 2
                    page.hasNext shouldBe false
                    page.nextCursor shouldBe null
                }
            }
        }

        Given("Board가 하나도 없을 때") {
            val fixture = ServiceFixture()
            every { fixture.boardRepositoryPort.findPage(null, 3) } returns emptyList<Board>().asFlow()

            When("getBoards를 호출하면") {
                val page = fixture.boardService.getBoards(BoardPageQuery(cursor = null, size = 2))

                Then("빈 페이지를 반환한다") {
                    page.items.shouldBeEmpty()
                    page.hasNext shouldBe false
                    page.nextCursor shouldBe null
                }
            }
        }

        Given("존재하는 Board와 유효한 Command가 주어졌을 때") {
            val fixture = ServiceFixture()
            val existingBoard = Board(id = 1L, title = "원래 제목", content = "원래 내용", createdAt = LocalDateTime.now())
            val command = UpdateBoardCommand(id = 1L, title = "새 제목", content = "새 내용")
            val updatedBoard = existingBoard.update(command.title, command.content)
            coEvery { fixture.boardRepositoryPort.findById(1L) } returns existingBoard
            coEvery { fixture.boardRepositoryPort.save(any()) } returns updatedBoard

            When("updateBoard를 호출하면") {
                val result = fixture.boardService.updateBoard(command)

                Then("변경된 Board를 저장하고 반환한다") {
                    result.title shouldBe "새 제목"
                    result.content shouldBe "새 내용"
                    coVerify { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("존재하지 않는 ID의 Command가 주어졌을 때") {
            val fixture = ServiceFixture()
            val command = UpdateBoardCommand(id = 999L, title = "새 제목", content = "새 내용")
            coEvery { fixture.boardRepositoryPort.findById(999L) } returns null

            When("updateBoard를 호출하면") {
                Then("BoardNotFoundException을 던지고 저장하지 않는다") {
                    shouldThrow<BoardNotFoundException> {
                        fixture.boardService.updateBoard(command)
                    }
                    coVerify(exactly = 0) { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("유효한 ID가 주어졌을 때") {
            val fixture = ServiceFixture()
            val id = 1L
            coEvery { fixture.boardRepositoryPort.deleteById(id) } returns Unit

            When("deleteBoard를 호출하면") {
                fixture.boardService.deleteBoard(id)

                Then("Repository의 deleteById를 호출한다") {
                    coVerify { fixture.boardRepositoryPort.deleteById(id) }
                }
            }
        }
    })
