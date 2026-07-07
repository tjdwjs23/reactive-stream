package demo.board.indexer.application.service

import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import demo.board.indexer.application.port.out.BoardIndexPort
import demo.board.indexer.domain.IndexedBoard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
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

        Given("CREATED/UPDATED 이벤트가 섞인 배치") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)
            every { port.saveAll(any()) } just Runs
            every { port.deleteAllById(any()) } just Runs

            When("applyAll하면") {
                val saved = slot<List<IndexedBoard>>()
                every { port.saveAll(capture(saved)) } just Runs
                service.applyAll(
                    listOf(
                        event(BoardChangeType.CREATED, boardId = 1L),
                        event(BoardChangeType.UPDATED, boardId = 5L),
                    ),
                )

                Then("이벤트 필드를 담은 IndexedBoard로 벌크 upsert한다") {
                    saved.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 5L)
                    saved.captured.first { it.id == 1L } shouldBe
                        IndexedBoard(
                            id = 1L,
                            title = "제목",
                            content = "10자 이상의 유효한 내용입니다.",
                            viewCount = 42L,
                            createdAt = createdAt,
                            authorId = 7L,
                        )
                }
                Then("삭제 대상이 없으면 deleteAllById는 호출하지 않는다") {
                    verify(exactly = 0) { port.deleteAllById(any()) }
                }
            }
        }

        Given("DELETED 이벤트가 섞인 배치") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)
            every { port.saveAll(any()) } just Runs
            every { port.deleteAllById(any()) } just Runs

            When("applyAll하면") {
                val deleted = slot<List<Long>>()
                every { port.deleteAllById(capture(deleted)) } just Runs
                service.applyAll(
                    listOf(
                        event(BoardChangeType.CREATED, boardId = 1L),
                        event(BoardChangeType.DELETED, boardId = 9L, title = null, content = null),
                    ),
                )

                Then("삭제 대상 id만 모아 벌크 삭제한다") {
                    deleted.captured shouldContainExactly listOf(9L)
                }
                Then("upsert 대상(1L)은 save로 간다") {
                    verify { port.saveAll(match { list -> list.any { it.id == 1L } }) }
                }
            }
        }

        Given("같은 게시글의 이벤트가 한 배치에 여러 건 도착") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)
            every { port.saveAll(any()) } just Runs
            every { port.deleteAllById(any()) } just Runs

            When("CREATED 뒤에 DELETED가 오면(마지막이 삭제)") {
                val saved = slot<List<IndexedBoard>>()
                val deleted = slot<List<Long>>()
                every { port.saveAll(capture(saved)) } just Runs
                every { port.deleteAllById(capture(deleted)) } just Runs
                service.applyAll(
                    listOf(
                        event(BoardChangeType.CREATED, boardId = 3L),
                        event(BoardChangeType.DELETED, boardId = 3L, title = null, content = null),
                    ),
                )

                Then("마지막 이벤트만 반영해 삭제만 수행한다(last write wins)") {
                    deleted.captured shouldContainExactly listOf(3L)
                    verify(exactly = 0) { port.saveAll(any()) }
                }
            }
        }

        Given("빈 배치") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)

            When("applyAll하면") {
                service.applyAll(emptyList())

                Then("아무 색인도 하지 않는다") {
                    verify(exactly = 0) { port.saveAll(any()) }
                    verify(exactly = 0) { port.deleteAllById(any()) }
                }
            }
        }

        Given("CREATED인데 title이 비어 있는(계약 위반) 이벤트") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port)

            When("applyAll하면") {
                Then("계약 위반으로 즉시 실패한다") {
                    shouldThrow<IllegalArgumentException> {
                        service.applyAll(listOf(event(BoardChangeType.CREATED, title = null)))
                    }
                }
            }
        }
    })
