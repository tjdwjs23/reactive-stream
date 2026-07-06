package demo.board.application.service

import demo.board.application.port.`in`.BoardSearchQuery
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardSearchHit
import demo.board.application.port.out.BoardSearchPort
import demo.board.domain.model.Board
import demo.board.support.NoOpObservabilityPort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.time.LocalDateTime
import java.util.Collections

// 실제 DB/ES 대신 인메모리 페이크 out-port로 재색인 순회·벌크·내결함성 흐름을 결정적으로 검증합니다.

// 키셋 페이지네이션(id 내림차순, cursor 미만)을 실제 어댑터와 동일하게 흉내 냅니다.
private class FakeBoardRepositoryPort(
    private val boards: List<Board>,
) : BoardRepositoryPort {
    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): Flow<Board> {
        val ordered = boards.sortedByDescending { it.id!! }
        val afterCursor = if (cursor == null) ordered else ordered.filter { it.id!! < cursor }
        return afterCursor.take(limit).asFlow()
    }

    override suspend fun save(board: Board): Board = throw UnsupportedOperationException("not needed")

    override suspend fun findById(id: Long): Board? = throw UnsupportedOperationException("not needed")

    override suspend fun deleteById(id: Long) = throw UnsupportedOperationException("not needed")

    override suspend fun addViewCountsBatch(deltas: Map<Long, Long>): Int =
        throw UnsupportedOperationException("not needed")
}

// indexAll 호출을 기록하고, 특정 id가 포함된 페이지에서 강제 실패시킬 수 있는 페이크.
private class FakeBoardSearchPort(
    private val failOnId: Long? = null,
    private val searchResult: List<BoardSearchHit> = emptyList(),
) : BoardSearchPort {
    val indexedIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    override suspend fun index(board: Board) {
        indexedIds.add(board.id!!)
    }

    override suspend fun indexAll(boards: List<Board>): Int {
        if (failOnId != null && boards.any { it.id == failOnId }) {
            throw IllegalStateException("forced bulk failure on page containing id=$failOnId")
        }
        boards.forEach { indexedIds.add(it.id!!) }
        return boards.size
    }

    override suspend fun deleteById(id: Long) {
        indexedIds.remove(id)
    }

    override fun search(
        keyword: String,
        size: Int,
    ): Flow<BoardSearchHit> = searchResult.asFlow()
}

private fun board(id: Long) = Board(id = id, title = "t$id", content = "c$id 내용입니다.", createdAt = LocalDateTime.now())

// REINDEX_PAGE_SIZE(=500)에 맞춘 데이터 크기. 페이지 경계 동작을 실제 상수 그대로 검증합니다.
private const val PAGE_SIZE = 500

class BoardSearchServiceTest :
    BehaviorSpec({

        Given("검색어로 검색할 때 - search()") {
            val hits =
                listOf(
                    BoardSearchHit(board(1L), score = 2.0, highlightedTitle = "<em>메일</em>", highlightedContent = null),
                    BoardSearchHit(
                        board(2L),
                        score = 1.0,
                        highlightedTitle = null,
                        highlightedContent = "<em>메일</em>과",
                    ),
                )
            val service =
                BoardSearchService(
                    FakeBoardSearchPort(searchResult = hits),
                    FakeBoardRepositoryPort(emptyList()),
                    NoOpObservabilityPort,
                )

            When("검색을 수행하면") {
                val result = service.search(BoardSearchQuery(keyword = "메일", size = 10))

                Then("포트가 흘려준 관련도순 Flow를 List로 모아 그대로 반환한다") {
                    result.map { it.board.id } shouldContainExactly listOf(1L, 2L)
                }
            }
        }

        Given("마지막 페이지가 꽉 차지 않는 데이터가 있을 때 - reindexAll()") {
            // 501건 → 500(꽉 참) + 1(짧은 마지막 페이지)
            val boards = (1L..(PAGE_SIZE + 1L)).map { board(it) }
            val searchPort = FakeBoardSearchPort()
            val service = BoardSearchService(searchPort, FakeBoardRepositoryPort(boards), NoOpObservabilityPort)

            When("전체 재색인을 실행하면") {
                val result = service.reindexAll()

                Then("모든 게시글이 중복 없이 정확히 한 번씩 색인된다") {
                    result.indexed shouldBe (PAGE_SIZE + 1L)
                    result.failed shouldBe 0L
                    searchPort.indexedIds.size shouldBe PAGE_SIZE + 1
                    searchPort.indexedIds.toSet() shouldBe (1L..(PAGE_SIZE + 1L)).toSet()
                }
            }
        }

        Given("마지막 페이지가 정확히 꽉 차는 데이터가 있을 때 - reindexAll()") {
            // 1000건 → 500 + 500 후 빈 페이지로 종료(무한 루프 방지 경계)
            val boards = (1L..(PAGE_SIZE * 2L)).map { board(it) }
            val searchPort = FakeBoardSearchPort()
            val service = BoardSearchService(searchPort, FakeBoardRepositoryPort(boards), NoOpObservabilityPort)

            When("전체 재색인을 실행하면") {
                val result = service.reindexAll()

                Then("빈 페이지에서 종료하며 모든 게시글이 색인된다") {
                    result.indexed shouldBe (PAGE_SIZE * 2L)
                    result.failed shouldBe 0L
                    searchPort.indexedIds.toSet() shouldBe (1L..(PAGE_SIZE * 2L)).toSet()
                }
            }
        }

        Given("특정 페이지 색인이 실패하도록 설정됐을 때 - reindexAll()") {
            // 501건: 첫 페이지(id 501..2)에 실패 대상 id=300이 포함 → 첫 페이지만 실패, 마지막 페이지(id 1)는 성공
            val boards = (1L..(PAGE_SIZE + 1L)).map { board(it) }
            val searchPort = FakeBoardSearchPort(failOnId = 300L)
            val service = BoardSearchService(searchPort, FakeBoardRepositoryPort(boards), NoOpObservabilityPort)

            When("전체 재색인을 실행하면") {
                val result = service.reindexAll()

                Then("실패한 페이지는 건너뛰고 나머지는 계속 색인하며 실패 건수를 집계한다") {
                    result.failed shouldBe PAGE_SIZE.toLong() // 실패한 첫 페이지(500건)
                    result.indexed shouldBe 1L // 마지막 페이지 1건만 성공
                    searchPort.indexedIds shouldContainExactly listOf(1L)
                }
            }
        }

        Given("게시글이 하나도 없을 때 - reindexAll()") {
            val searchPort = FakeBoardSearchPort()
            val service = BoardSearchService(searchPort, FakeBoardRepositoryPort(emptyList()), NoOpObservabilityPort)

            When("전체 재색인을 실행하면") {
                val result = service.reindexAll()

                Then("색인/실패 모두 0이며 아무것도 색인하지 않는다") {
                    result.indexed shouldBe 0L
                    result.failed shouldBe 0L
                    searchPort.indexedIds.size shouldBe 0
                }
            }
        }
    })
