package demo.board.adapter.out.search

import demo.board.domain.model.Board
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import io.kotest.matchers.string.shouldContain as stringShouldContain

// 실제 Elasticsearch(+Nori) 컨테이너에 색인/검색을 수행하는 통합 테스트.
// ES는 refresh 간격 뒤에야 검색에 반영되므로, 각 색인/삭제 후 인덱스를 명시적으로 refresh해 결정적으로 검증합니다.
@SpringBootTest
class BoardSearchAdapterTest(
    @Autowired private val boardSearchAdapter: BoardSearchAdapter,
    @Autowired private val operations: ReactiveElasticsearchOperations,
) : BehaviorSpec({

        // 색인 직후 검색 가능하도록 'boards' 인덱스를 강제 refresh
        suspend fun refresh() {
            operations.indexOps(BoardDocument::class.java).refresh().awaitFirstOrNull()
        }

        Given("한글 게시글이 색인되어 있을 때") {
            // 다른 테스트와 충돌하지 않도록 고유한 id/검색어를 사용합니다.
            val mailBoard = Board(id = 9001L, title = "카카오메일 공지사항", content = "스팸 필터 개선 안내입니다.")
            val contactBoard = Board(id = 9002L, title = "주소록 동기화 안내", content = "연락처 동기화. 메일과는 무관합니다.")
            boardSearchAdapter.index(mailBoard)
            boardSearchAdapter.index(contactBoard)
            refresh()

            When("'메일'로 검색하면") {
                val hits = boardSearchAdapter.search("메일", 10).toList()
                val ids = hits.map { it.board.id }

                Then("Nori가 조사를 분리해 '메일과'가 있는 게시글도 매칭된다") {
                    // 제목에 '카카오메일'이 있는 9001과, 본문에 '메일과'가 있는 9002 모두 매칭
                    ids shouldContain 9001L
                    ids shouldContain 9002L
                }

                Then("제목 매칭(가중치 title^2)이 관련도 상위에 온다") {
                    hits.first().board.id shouldBe 9001L
                }

                Then("매칭 부분이 <em>으로 하이라이트된다") {
                    val contactHit = hits.first { it.board.id == 9002L }
                    contactHit.highlightedContent!!.stringShouldContain("<em>메일</em>")
                }
            }

            When("색인에서 9001을 삭제하고 다시 검색하면") {
                boardSearchAdapter.deleteById(9001L)
                refresh()
                val idsAfterDelete = boardSearchAdapter.search("메일", 10).toList().map { it.board.id }

                Then("삭제된 게시글은 더 이상 검색되지 않는다") {
                    idsAfterDelete shouldNotContain 9001L
                }
            }
        }

        Given("여러 게시글을 벌크로 색인할 때 - indexAll") {
            val bulk =
                listOf(
                    Board(id = 9101L, title = "벌크 색인 게시글 하나", content = "재색인 테스트 내용입니다."),
                    Board(id = 9102L, title = "벌크 색인 게시글 둘", content = "재색인 테스트 내용입니다."),
                    Board(id = 9103L, title = "벌크 색인 게시글 셋", content = "재색인 테스트 내용입니다."),
                )

            When("indexAll로 한 번에 색인하면") {
                val count = boardSearchAdapter.indexAll(bulk)
                refresh()
                val ids = boardSearchAdapter.search("벌크", 10).toList().map { it.board.id }

                Then("색인된 문서 수를 반환하고 모두 검색된다") {
                    count shouldBe 3
                    ids shouldContain 9101L
                    ids shouldContain 9102L
                    ids shouldContain 9103L
                }
            }

            When("빈 목록을 indexAll하면") {
                Then("아무 것도 색인하지 않고 0을 반환한다") {
                    boardSearchAdapter.indexAll(emptyList()) shouldBe 0
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = TestContainers.registerAll(registry)
    }
}
