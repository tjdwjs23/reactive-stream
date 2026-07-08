package demo.board.application.service

import demo.board.application.port.`in`.BoardSearchQuery
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardSearchHit
import demo.board.application.port.out.BoardSearchPort
import demo.board.domain.model.Board
import demo.board.support.NoOpObservabilityPort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.Collections

// 실제 DB/ES 대신 인메모리 페이크 out-port로 재색인 순회·벌크·내결함성·alias 스왑 흐름을 결정적으로 검증합니다.

// 키셋 페이지네이션(id 내림차순, cursor 미만)을 실제 어댑터와 동일하게 흉내 냅니다.
private class FakeBoardRepositoryPort(
    private val boards: List<Board>,
) : BoardRepositoryPort {
    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Board> {
        val ordered = boards.sortedByDescending { it.id!! }
        val afterCursor = if (cursor == null) ordered else ordered.filter { it.id!! < cursor }
        return afterCursor.take(limit)
    }

    override fun save(board: Board): Board = throw UnsupportedOperationException("not needed")

    override fun findById(id: Long): Board? = throw UnsupportedOperationException("not needed")

    override fun deleteById(id: Long) = throw UnsupportedOperationException("not needed")

    override fun addViewCountsBatch(deltas: Map<Long, Long>): List<Board> =
        throw UnsupportedOperationException("not needed")
}

// 재색인의 alias 흐름(새 버전 인덱스 생성 → indexInto → promote/discard)을 기록하는 페이크.
// failOnId가 포함된 페이지는 강제 실패시켜 부분 실패 시 "스왑하지 않고 폐기(롤백)"를 검증합니다.
private class FakeBoardSearchPort(
    private val failOnId: Long? = null,
    private val searchResult: List<BoardSearchHit> = emptyList(),
) : BoardSearchPort {
    val indexedIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    @Volatile var createdIndex: String? = null

    @Volatile var promotedIndex: String? = null

    @Volatile var discardedIndex: String? = null

    override fun createNewVersionIndex(): String {
        val name = "boards_test_${System.identityHashCode(this)}"
        createdIndex = name
        return name
    }

    override fun indexInto(
        boards: List<Board>,
        indexName: String,
    ): Int {
        if (failOnId != null && boards.any { it.id == failOnId }) {
            throw IllegalStateException("forced bulk failure on page containing id=$failOnId")
        }
        boards.forEach { indexedIds.add(it.id!!) }
        return boards.size
    }

    override fun promote(indexName: String) {
        promotedIndex = indexName
    }

    override fun deleteVersionIndex(indexName: String) {
        discardedIndex = indexName
    }

    override fun search(
        keyword: String,
        size: Int,
    ): List<BoardSearchHit> = searchResult
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

                Then("포트가 준 관련도순 결과를 그대로 반환한다") {
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

                Then("새 버전 인덱스에 모든 게시글을 중복 없이 색인하고 alias를 스왑한다") {
                    result.indexed shouldBe (PAGE_SIZE + 1L)
                    result.failed shouldBe 0L
                    result.swapped shouldBe true
                    searchPort.indexedIds.toSet() shouldBe (1L..(PAGE_SIZE + 1L)).toSet()
                    searchPort.promotedIndex shouldBe searchPort.createdIndex
                    searchPort.discardedIndex.shouldBeNull()
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

                Then("빈 페이지에서 종료하며 모든 게시글을 색인하고 스왑한다") {
                    result.indexed shouldBe (PAGE_SIZE * 2L)
                    result.failed shouldBe 0L
                    result.swapped shouldBe true
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

                Then("실패한 페이지는 건너뛰고 실패 건수를 집계하며, 불완전 인덱스를 폐기하고 스왑하지 않는다(자동 롤백)") {
                    result.failed shouldBe PAGE_SIZE.toLong() // 실패한 첫 페이지(500건)
                    result.indexed shouldBe 1L // 마지막 페이지 1건만 성공
                    result.swapped shouldBe false
                    searchPort.indexedIds shouldContainExactly listOf(1L)
                    searchPort.promotedIndex.shouldBeNull()
                    searchPort.discardedIndex shouldBe searchPort.createdIndex
                }
            }
        }

        Given("게시글이 하나도 없을 때 - reindexAll()") {
            val searchPort = FakeBoardSearchPort()
            val service = BoardSearchService(searchPort, FakeBoardRepositoryPort(emptyList()), NoOpObservabilityPort)

            When("전체 재색인을 실행하면") {
                val result = service.reindexAll()

                Then("색인/실패 모두 0이며, 빈 새 인덱스를 스왑한다") {
                    result.indexed shouldBe 0L
                    result.failed shouldBe 0L
                    result.swapped shouldBe true
                    searchPort.indexedIds.size shouldBe 0
                    searchPort.promotedIndex shouldBe searchPort.createdIndex
                }
            }
        }
    })
