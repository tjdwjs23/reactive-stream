package demo.board.indexer.application.service

import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import demo.board.indexer.application.port.out.BoardIndexPort
import demo.board.indexer.domain.IndexedBoard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDateTime

class BoardIndexServiceTest :
    BehaviorSpec({

        val createdAt = LocalDateTime.of(2026, 7, 7, 10, 0)

        fun event(
            type: BoardChangeType,
            boardId: Long = 1L,
            title: String? = "제목",
            content: String? = "10자 이상의 유효한 내용입니다.",
        ) = BoardChangedEvent(
            eventId = "evt-$boardId",
            boardId = boardId,
            type = type,
            title = title,
            content = content,
            authorId = 7L,
            viewCount = 42L,
            createdAt = createdAt,
            occurredAt = Instant.parse("2026-07-07T01:00:00Z"),
        )

        Given("CREATED 이벤트") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)
            every { port.save(any()) } just Runs

            When("apply하면") {
                service.apply(event(BoardChangeType.CREATED))

                Then("이벤트 필드를 담은 IndexedBoard로 색인(upsert)한다") {
                    verify {
                        port.save(
                            IndexedBoard(
                                id = 1L,
                                title = "제목",
                                content = "10자 이상의 유효한 내용입니다.",
                                viewCount = 42L,
                                createdAt = createdAt,
                                authorId = 7L,
                            ),
                        )
                    }
                }
            }
        }

        Given("UPDATED 이벤트") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)
            every { port.save(any()) } just Runs

            When("apply하면") {
                service.apply(event(BoardChangeType.UPDATED, boardId = 5L))

                Then("같은 문서를 덮어쓴다(save)") {
                    verify { port.save(match { it.id == 5L }) }
                }
            }
        }

        Given("DELETED 이벤트") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)
            every { port.deleteById(any()) } just Runs

            When("apply하면") {
                service.apply(event(BoardChangeType.DELETED, boardId = 9L, title = null, content = null))

                Then("색인에서 제거만 하고 save는 부르지 않는다") {
                    verify { port.deleteById(9L) }
                    verify(exactly = 0) { port.save(any()) }
                }
            }
        }

        Given("CREATED인데 title이 비어 있는(계약 위반) 이벤트") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)

            When("apply하면") {
                Then("계약 위반으로 즉시 실패한다") {
                    shouldThrow<IllegalArgumentException> {
                        service.apply(event(BoardChangeType.CREATED, title = null))
                    }
                }
            }
        }
    })
