package demo.board.application.service

import demo.board.application.port.`in`.BoardPageQuery
import demo.board.application.port.`in`.CreateBoardCommand
import demo.board.application.port.`in`.DeleteBoardCommand
import demo.board.application.port.`in`.UpdateBoardCommand
import demo.board.application.port.out.BoardEventOutboxPort
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardViewCountPort
import demo.board.application.port.out.TransactionRunnerPort
import demo.board.domain.exception.BoardAccessDeniedException
import demo.board.domain.exception.BoardNotFoundException
import demo.board.domain.model.Board
import demo.board.support.NoOpObservabilityPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.containExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.LocalDateTime

private class ServiceFixture {
    val boardRepositoryPort = mockk<BoardRepositoryPort>()
    val boardViewCountPort = mockk<BoardViewCountPort>()

    // 아웃박스 기록은 쓰기 경로의 부수효과이므로 relaxed 목으로 둡니다(record는 무동작).
    val boardEventOutboxPort = mockk<BoardEventOutboxPort>(relaxed = true)

    // 트랜잭션 경계는 테스트에서 블록을 그대로 실행하는 pass-through로 대체합니다
    // (실제 원자성/롤백은 JPA 통합테스트가 검증하고, 여기서는 서비스 오케스트레이션만 봅니다).
    val transactionRunner =
        object : TransactionRunnerPort {
            override fun <T> execute(block: () -> T): T = block()
        }

    // 생성 시각 주입용 시계. 이 테스트는 createdAt 값 자체를 검증하지 않으므로 시스템 시계로 충분합니다.
    val boardService =
        BoardService(
            boardRepositoryPort,
            boardViewCountPort,
            boardEventOutboxPort,
            transactionRunner,
            NoOpObservabilityPort,
            Clock.systemDefaultZone(),
        )
}

class BoardServiceTest :
    BehaviorSpec({

        Given("유효한 CreateBoardCommand가 주어졌을 때") {
            val fixture = ServiceFixture()
            val command = CreateBoardCommand(title = "제목", content = "10자 이상의 유효한 내용입니다.", authorId = 7L)
            val savedBoard =
                Board(
                    id = 1L,
                    title = "제목",
                    content = "10자 이상의 유효한 내용입니다.",
                    createdAt = LocalDateTime.now(),
                    authorId = 7L,
                )
            every { fixture.boardRepositoryPort.save(any()) } returns savedBoard

            When("createBoard를 호출하면") {
                val result = fixture.boardService.createBoard(command)

                Then("작성자 id를 담아 저장하고 Board를 반환한다") {
                    result.id shouldBe 1L
                    result.title shouldBe "제목"
                    // 커맨드의 authorId가 저장되는 Board로 전달되는지 검증
                    verify { fixture.boardRepositoryPort.save(match { it.authorId == 7L }) }
                }
            }
        }

        Given("존재하는 ID가 주어졌을 때") {
            val fixture = ServiceFixture()
            val board = Board(id = 1L, title = "제목", content = "내용", viewCount = 10L, createdAt = LocalDateTime.now())
            every { fixture.boardRepositoryPort.findById(1L) } returns board
            // 조회 시 Redis 델타 +1, 아직 DB에 반영 안 된 누적 델타(3)를 반환한다고 가정
            every { fixture.boardViewCountPort.increment(1L) } returns 3L

            When("getBoard를 호출하면") {
                val result = fixture.boardService.getBoard(1L)

                Then("조회수를 증가시키고, DB 누적값 + 미반영 델타를 조회수로 반환한다") {
                    result.id shouldBe 1L
                    result.title shouldBe "제목"
                    result.viewCount shouldBe 13L
                    verify { fixture.boardViewCountPort.increment(1L) }
                }
            }
        }

        Given("존재하지 않는 ID가 주어졌을 때") {
            val fixture = ServiceFixture()
            every { fixture.boardRepositoryPort.findById(999L) } returns null

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
            val board = Board(id = 1L, title = "제목", content = "내용", viewCount = 42L, createdAt = LocalDateTime.now())
            every { fixture.boardRepositoryPort.findById(1L) } returns board
            every { fixture.boardViewCountPort.increment(1L) } throws RuntimeException("redis down")

            When("getBoard를 호출하면") {
                val result = fixture.boardService.getBoard(1L)

                Then("조회는 실패하지 않고, 미반영 델타 없이 DB 누적값으로 응답한다") {
                    result.id shouldBe 1L
                    result.viewCount shouldBe 42L // 델타 0으로 강등(best-effort)
                    verify { fixture.boardViewCountPort.increment(1L) }
                }
            }
        }

        Given("다음 페이지가 있을 만큼 Board가 많을 때") {
            val fixture = ServiceFixture()
            // size=2 요청 → 서비스는 hasNext 판정을 위해 size+1(=3)건을 id 내림차순으로 조회한다
            val rows =
                listOf(
                    Board(id = 5L, title = "제목5", content = "내용5", createdAt = LocalDateTime.now()),
                    Board(id = 4L, title = "제목4", content = "내용4", createdAt = LocalDateTime.now()),
                    Board(id = 3L, title = "제목3", content = "내용3", createdAt = LocalDateTime.now()),
                )
            every { fixture.boardRepositoryPort.findPage(null, 3) } returns rows
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
                    Board(id = 2L, title = "제목2", content = "내용2", createdAt = LocalDateTime.now()),
                    Board(id = 1L, title = "제목1", content = "내용1", createdAt = LocalDateTime.now()),
                )
            every { fixture.boardRepositoryPort.findPage(10L, 3) } returns rows
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
            every { fixture.boardRepositoryPort.findPage(null, 3) } returns emptyList<Board>()
            When("getBoards를 호출하면") {
                val page = fixture.boardService.getBoards(BoardPageQuery(cursor = null, size = 2))

                Then("빈 페이지를 반환한다") {
                    page.items.shouldBeEmpty()
                    page.hasNext shouldBe false
                    page.nextCursor shouldBe null
                }
            }
        }

        Given("소유자가 자신의 Board를 유효한 Command로 수정할 때") {
            val fixture = ServiceFixture()
            val existingBoard =
                Board(id = 1L, title = "원래 제목", content = "원래 내용입니다.", createdAt = LocalDateTime.now(), authorId = 7L)
            val command =
                UpdateBoardCommand(id = 1L, title = "새 제목", content = "새 내용은 열 자 이상입니다", requesterId = 7L)
            val updatedBoard = existingBoard.update(command.title, command.content)
            every { fixture.boardRepositoryPort.findById(1L) } returns existingBoard
            every { fixture.boardRepositoryPort.save(any()) } returns updatedBoard

            When("updateBoard를 호출하면") {
                val result = fixture.boardService.updateBoard(command)

                Then("변경된 Board를 저장하고 반환한다") {
                    result.title shouldBe "새 제목"
                    result.content shouldBe "새 내용은 열 자 이상입니다"
                    verify { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("소유자가 아닌 사용자가 남의 Board를 수정하려 할 때") {
            val fixture = ServiceFixture()
            val existingBoard =
                Board(id = 1L, title = "원래 제목", content = "원래 내용입니다.", createdAt = LocalDateTime.now(), authorId = 7L)
            // 요청자(99L) ≠ 작성자(7L), 관리자도 아님
            val command =
                UpdateBoardCommand(id = 1L, title = "탈취 제목", content = "남의 글을 고치려는 시도입니다", requesterId = 99L)
            every { fixture.boardRepositoryPort.findById(1L) } returns existingBoard

            When("updateBoard를 호출하면") {
                Then("BoardAccessDeniedException을 던지고 저장하지 않는다") {
                    shouldThrow<BoardAccessDeniedException> {
                        fixture.boardService.updateBoard(command)
                    }
                    verify(exactly = 0) { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("관리자가 남의 Board를 수정할 때") {
            val fixture = ServiceFixture()
            val existingBoard =
                Board(id = 1L, title = "원래 제목", content = "원래 내용입니다.", createdAt = LocalDateTime.now(), authorId = 7L)
            // 요청자(99L)는 작성자가 아니지만 관리자 → 허용
            val command =
                UpdateBoardCommand(
                    id = 1L,
                    title = "관리자 수정",
                    content = "관리자가 정리하는 내용입니다",
                    requesterId = 99L,
                    requesterIsAdmin = true,
                )
            val updatedBoard = existingBoard.update(command.title, command.content)
            every { fixture.boardRepositoryPort.findById(1L) } returns existingBoard
            every { fixture.boardRepositoryPort.save(any()) } returns updatedBoard

            When("updateBoard를 호출하면") {
                Then("소유자가 아니어도 수정에 성공한다") {
                    val result = fixture.boardService.updateBoard(command)
                    result.title shouldBe "관리자 수정"
                    verify { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("존재하지 않는 ID의 Command가 주어졌을 때") {
            val fixture = ServiceFixture()
            val command = UpdateBoardCommand(id = 999L, title = "새 제목", content = "새 내용입니다열자", requesterId = 7L)
            every { fixture.boardRepositoryPort.findById(999L) } returns null

            When("updateBoard를 호출하면") {
                Then("BoardNotFoundException을 던지고 저장하지 않는다") {
                    shouldThrow<BoardNotFoundException> {
                        fixture.boardService.updateBoard(command)
                    }
                    verify(exactly = 0) { fixture.boardRepositoryPort.save(any()) }
                }
            }
        }

        Given("소유자가 자신의 Board를 삭제할 때") {
            val fixture = ServiceFixture()
            val id = 1L
            val existingBoard =
                Board(id = id, title = "제목", content = "내용입니다열자", createdAt = LocalDateTime.now(), authorId = 7L)
            every { fixture.boardRepositoryPort.findById(id) } returns existingBoard
            every { fixture.boardRepositoryPort.deleteById(id) } returns Unit

            When("deleteBoard를 호출하면") {
                fixture.boardService.deleteBoard(DeleteBoardCommand(id = id, requesterId = 7L))

                Then("Repository의 deleteById를 호출한다") {
                    verify { fixture.boardRepositoryPort.deleteById(id) }
                }
            }
        }

        Given("소유자가 아닌 사용자가 남의 Board를 삭제하려 할 때") {
            val fixture = ServiceFixture()
            val id = 1L
            val existingBoard =
                Board(id = id, title = "제목", content = "내용입니다열자", createdAt = LocalDateTime.now(), authorId = 7L)
            every { fixture.boardRepositoryPort.findById(id) } returns existingBoard

            When("deleteBoard를 호출하면") {
                Then("BoardAccessDeniedException을 던지고 deleteById를 호출하지 않는다") {
                    shouldThrow<BoardAccessDeniedException> {
                        fixture.boardService.deleteBoard(DeleteBoardCommand(id = id, requesterId = 99L))
                    }
                    verify(exactly = 0) { fixture.boardRepositoryPort.deleteById(any()) }
                }
            }
        }

        Given("삭제하려는 Board가 존재하지 않을 때") {
            val fixture = ServiceFixture()
            every { fixture.boardRepositoryPort.findById(404L) } returns null

            When("deleteBoard를 호출하면") {
                Then("BoardNotFoundException을 던지고 deleteById를 호출하지 않는다") {
                    shouldThrow<BoardNotFoundException> {
                        fixture.boardService.deleteBoard(DeleteBoardCommand(id = 404L, requesterId = 7L))
                    }
                    verify(exactly = 0) { fixture.boardRepositoryPort.deleteById(any()) }
                }
            }
        }
    })
