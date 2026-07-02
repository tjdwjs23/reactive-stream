package demo.hexagonal.hexagonalback.application.service

import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardCommand
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardCommand
import demo.hexagonal.hexagonalback.application.port.out.BoardRepositoryPort
import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.model.Board
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
import kotlinx.coroutines.flow.toList
import java.time.LocalDateTime

private class ServiceFixture {
    val boardRepositoryPort = mockk<BoardRepositoryPort>()
    val boardService = BoardService(boardRepositoryPort)
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
            val board = Board(id = 1L, title = "제목", content = "내용")
            coEvery { fixture.boardRepositoryPort.findById(1L) } returns board

            When("getBoard를 호출하면") {
                val result = fixture.boardService.getBoard(1L)

                Then("해당 Board를 반환한다") {
                    result.id shouldBe 1L
                    result.title shouldBe "제목"
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

        Given("Board 목록이 존재할 때") {
            val fixture = ServiceFixture()
            val boards =
                listOf(
                    Board(id = 1L, title = "제목1", content = "내용1"),
                    Board(id = 2L, title = "제목2", content = "내용2"),
                )
            every { fixture.boardRepositoryPort.findAll() } returns boards.asFlow()

            When("getAllBoards를 호출하면") {
                val result = fixture.boardService.getAllBoards().toList()

                Then("전체 Board 목록을 반환한다") {
                    result shouldHaveSize 2
                    result.map { it.id } should containExactly(1L, 2L)
                }
            }
        }

        Given("Board가 하나도 없을 때") {
            val fixture = ServiceFixture()
            every { fixture.boardRepositoryPort.findAll() } returns emptyList<Board>().asFlow()

            When("getAllBoards를 호출하면") {
                val result = fixture.boardService.getAllBoards().toList()

                Then("빈 목록을 반환한다") {
                    result.shouldBeEmpty()
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
