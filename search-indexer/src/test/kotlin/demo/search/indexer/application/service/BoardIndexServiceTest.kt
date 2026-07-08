package demo.search.indexer.application.service

import demo.search.events.BoardChangeType
import demo.search.events.BoardChangedEvent
import demo.search.indexer.application.port.out.BoardIndexPort
import demo.search.indexer.application.port.out.IndexerObservabilityPort
import demo.search.indexer.domain.IndexedBoard
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

        // 관측 포트의 테스트 더블. recordIndexingBatch는 실제로 블록을 실행해 saveAll/deleteAll이 호출되게 하고,
        // 카운터 메서드는 relaxed로 무해하게 흡수합니다(색인 로직 검증에는 관여하지 않음).
        fun observability(): IndexerObservabilityPort =
            mockk<IndexerObservabilityPort>(relaxed = true).also {
                every { it.recordIndexingBatch(any()) } answers { firstArg<() -> Unit>().invoke() }
            }

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
            val service = BoardIndexService(port, observability())
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
            val service = BoardIndexService(port, observability())
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
            val service = BoardIndexService(port, observability())
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

        Given("색인/삭제가 섞인 배치의 관측") {
            val port = mockk<BoardIndexPort>()
            val obs = observability()
            val service = BoardIndexService(port, obs)
            every { port.saveAll(any()) } just Runs
            every { port.deleteAllById(any()) } just Runs

            When("applyAll하면") {
                service.applyAll(
                    listOf(
                        event(BoardChangeType.CREATED, boardId = 1L),
                        event(BoardChangeType.UPDATED, boardId = 2L),
                        event(BoardChangeType.DELETED, boardId = 9L, title = null, content = null),
                    ),
                )

                Then("ES 쓰기를 타이머로 감싸고, 반영된 upsert·삭제 문서 수를 기록한다") {
                    verify(exactly = 1) { obs.recordIndexingBatch(any()) }
                    verify(exactly = 1) { obs.boardsIndexed(2) }
                    verify(exactly = 1) { obs.boardsDeleted(1) }
                }
            }
        }

        Given("빈 배치") {
            val port = mockk<BoardIndexPort>()
            val obs = observability()
            val service = BoardIndexService(port, obs)

            When("applyAll하면") {
                service.applyAll(emptyList())

                Then("아무 색인도 관측도 하지 않는다") {
                    verify(exactly = 0) { port.saveAll(any()) }
                    verify(exactly = 0) { port.deleteAllById(any()) }
                    verify(exactly = 0) { obs.recordIndexingBatch(any()) }
                }
            }
        }

        Given("CREATED인데 title이 비어 있는(계약 위반) 이벤트") {
            val port = mockk<BoardIndexPort>()
            val service = BoardIndexService(port, observability())

            When("applyAll하면") {
                Then("계약 위반으로 즉시 실패한다") {
                    shouldThrow<IllegalArgumentException> {
                        service.applyAll(listOf(event(BoardChangeType.CREATED, title = null)))
                    }
                }
            }
        }
    })
