package demo.search.adapter.out.search

import demo.search.domain.model.Board
import demo.search.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime
import io.kotest.matchers.string.shouldContain as stringShouldContain

// 실제 Elasticsearch(+Nori) 컨테이너에 alias 기반 재색인/검색을 수행하는 통합 테스트.
// 검색은 alias 'boards'를 통해 이뤄지므로, 데이터는 "새 버전 인덱스 생성 → indexInto → promote(alias 스왑)"
// 흐름으로 넣는다(reindexAll이 내부에서 쓰는 것과 동일 경로). ES refresh 간격 뒤에야 검색에 반영되므로 명시적으로 refresh한다.
@SpringBootTest
class BoardSearchAdapterTest(
    @Autowired private val boardSearchAdapter: BoardSearchAdapter,
    @Autowired private val operations: ElasticsearchOperations,
) : BehaviorSpec({

        // 색인 직후 검색 가능하도록 alias('boards')를 강제 refresh
        fun refresh() {
            operations.indexOps(BoardDocument::class.java).refresh()
        }

        // 새 버전 인덱스에 색인한 뒤 alias로 승격(reindexAll과 동일 경로). 반환값 = 승격된 버전 인덱스명.
        fun indexAndPromote(boards: List<Board>): String {
            val version = boardSearchAdapter.createNewVersionIndex()
            boardSearchAdapter.indexInto(boards, version)
            boardSearchAdapter.promote(version)
            refresh()
            return version
        }

        Given("한글 게시글을 새 버전 인덱스에 색인·승격했을 때") {
            val mailBoard =
                Board(id = 9001L, title = "카카오메일 공지사항", content = "스팸 필터 개선 안내입니다.", createdAt = LocalDateTime.now())
            val contactBoard =
                Board(
                    id = 9002L,
                    title = "주소록 동기화 안내",
                    content = "연락처 동기화. 메일과는 무관합니다.",
                    createdAt = LocalDateTime.now(),
                )
            indexAndPromote(listOf(mailBoard, contactBoard))

            When("'메일'로 검색하면") {
                val hits = boardSearchAdapter.search("메일", 10)
                val ids = hits.map { it.board.id }

                Then("Nori가 조사를 분리해 '메일과'가 있는 게시글도 매칭된다") {
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

        Given("새 버전 인덱스 생성/색인/삭제 - createNewVersionIndex / indexInto / deleteVersionIndex") {
            When("버전 인덱스를 만들면") {
                val version = boardSearchAdapter.createNewVersionIndex()

                Then("'boards_' 접두사의 유일한 이름을 반환하고 존재한다") {
                    version shouldStartWith "boards_"
                    operations.indexOps(IndexCoordinates.of(version)).exists() shouldBe true
                }

                Then("indexInto로 색인한 문서 수를 반환하고, 빈 목록은 0을 반환한다") {
                    val bulk =
                        listOf(
                            Board(
                                id = 9101L,
                                title = "벌크 하나",
                                content = "재색인 테스트 내용입니다.",
                                createdAt = LocalDateTime.now(),
                            ),
                            Board(
                                id = 9102L,
                                title = "벌크 둘",
                                content = "재색인 테스트 내용입니다.",
                                createdAt = LocalDateTime.now(),
                            ),
                        )
                    boardSearchAdapter.indexInto(bulk, version) shouldBe 2
                    boardSearchAdapter.indexInto(emptyList(), version) shouldBe 0
                }

                Then("deleteVersionIndex로 폐기하면 인덱스가 사라진다(스왑 없이 롤백)") {
                    boardSearchAdapter.deleteVersionIndex(version)
                    operations.indexOps(IndexCoordinates.of(version)).exists() shouldBe false
                }
            }
        }

        Given("이미 한 버전이 alias에 승격돼 있을 때 - promote(무중단 스왑)") {
            indexAndPromote(
                listOf(
                    Board(id = 9201L, title = "구버전 전용 게시글", content = "예전 색인 내용입니다.", createdAt = LocalDateTime.now()),
                ),
            )

            When("새 버전을 만들어 다른 데이터로 승격하면") {
                indexAndPromote(
                    listOf(
                        Board(
                            id = 9202L,
                            title = "신버전 전용 게시글",
                            content = "새 색인 내용입니다.",
                            createdAt = LocalDateTime.now(),
                        ),
                    ),
                )
                val ids = boardSearchAdapter.search("게시글", 10).map { it.board.id }

                Then("검색은 새 버전 데이터만 보고(무중단 스왑), 구버전 문서는 사라진다") {
                    ids shouldContain 9202L
                    ids shouldNotContain 9201L
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
