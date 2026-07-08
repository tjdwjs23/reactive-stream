package demo.board.adapter.out.search

import demo.board.domain.model.Board
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime
import io.kotest.matchers.string.shouldContain as stringShouldContain

// 실제 Elasticsearch(+Nori) 컨테이너에 색인/검색을 수행하는 통합 테스트.
// ES는 refresh 간격 뒤에야 검색에 반영되므로, 색인 후 인덱스를 명시적으로 refresh해 결정적으로 검증합니다.
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
            val mailBoard =
                Board(id = 9001L, title = "카카오메일 공지사항", content = "스팸 필터 개선 안내입니다.", createdAt = LocalDateTime.now())
            val contactBoard =
                Board(
                    id = 9002L,
                    title = "주소록 동기화 안내",
                    content = "연락처 동기화. 메일과는 무관합니다.",
                    createdAt = LocalDateTime.now(),
                )
            boardSearchAdapter.indexAll(listOf(mailBoard, contactBoard))
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
        }

        Given("여러 게시글을 벌크로 색인할 때 - indexAll") {
            val bulk =
                listOf(
                    Board(
                        id = 9101L,
                        title = "벌크 색인 게시글 하나",
                        content = "재색인 테스트 내용입니다.",
                        createdAt = LocalDateTime.now(),
                    ),
                    Board(
                        id = 9102L,
                        title = "벌크 색인 게시글 둘",
                        content = "재색인 테스트 내용입니다.",
                        createdAt = LocalDateTime.now(),
                    ),
                    Board(
                        id = 9103L,
                        title = "벌크 색인 게시글 셋",
                        content = "재색인 테스트 내용입니다.",
                        createdAt = LocalDateTime.now(),
                    ),
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

        Given("정본에 없는 고아 문서가 색인돼 있을 때 - pruneExcept") {
            // 공유 인덱스를 오염시키지 않도록 낮은 id(≤102)만 씁니다. maxKeep=102라 다른 테스트의 문서(9001+)는
            // "max(keepIds) 초과" 가드로 절대 지워지지 않습니다(같은 인덱스를 공유하는 통합 테스트 간 격리).
            val keep1 =
                Board(id = 101L, title = "정본 유지 문서 하나", content = "prune 유지 대상입니다.", createdAt = LocalDateTime.now())
            val keep2 =
                Board(id = 102L, title = "정본 유지 문서 둘", content = "prune 유지 대상입니다.", createdAt = LocalDateTime.now())
            val orphan = Board(id = 99L, title = "고아 문서", content = "정본에서 삭제된 문서입니다.", createdAt = LocalDateTime.now())
            // 재색인 중 갓 생성된(스냅샷 이후) 문서를 흉내 내는 고-id 문서. max(keepIds) 초과라 prune 대상이 아니어야 합니다.
            val future =
                Board(id = 8888L, title = "미래 문서", content = "재색인 스냅샷 이후 생성분입니다.", createdAt = LocalDateTime.now())
            boardSearchAdapter.indexAll(listOf(keep1, keep2, orphan, future))
            refresh()

            When("pruneExcept(정본 id = {101,102})를 호출하면") {
                val pruned = boardSearchAdapter.pruneExcept(setOf(101L, 102L))
                refresh()

                Then("정본에 없는 고아(99)만 삭제되고, 정본 문서(101,102)는 남는다") {
                    pruned shouldBe 1
                    (operations.get("99", BoardDocument::class.java).awaitFirstOrNull() == null) shouldBe true
                    (operations.get("101", BoardDocument::class.java).awaitFirstOrNull() != null) shouldBe true
                    (operations.get("102", BoardDocument::class.java).awaitFirstOrNull() != null) shouldBe true
                }

                Then("max(keepIds)=102를 초과하는 문서(8888)는 재색인 중 생성분일 수 있어 가드로 보존된다") {
                    (operations.get("8888", BoardDocument::class.java).awaitFirstOrNull() != null) shouldBe true
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
