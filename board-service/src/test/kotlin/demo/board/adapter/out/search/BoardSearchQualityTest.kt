package demo.board.adapter.out.search

import demo.board.domain.model.Board
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime
import io.kotest.matchers.string.shouldContain as stringShouldContain

// 검색 "품질" 회귀 테스트. 배선(wiring)이 아니라 recall/ranking 같은 검색 결과의 품질을 고정합니다.
// Spoqa 사례의 원칙: 요구사항이 생길 때마다 케이스를 추가하되 "이전 요구사항 케이스는 지우지 않고 누적"해,
// 분석기/쿼리/가중치를 바꿔도 과거 품질이 깨지지 않게 합니다. 각 케이스에 [REQ-n] 태그로 어떤 요구사항인지 남깁니다.
//
// 실 ES(+Nori) 컨테이너에 실제 코퍼스를 색인해 검증합니다. 데이터는 alias 재색인 흐름으로 넣습니다.
@SpringBootTest
class BoardSearchQualityTest(
    @Autowired private val boardSearchAdapter: BoardSearchAdapter,
    @Autowired private val operations: ElasticsearchOperations,
) : BehaviorSpec({

        fun refresh() = operations.indexOps(BoardDocument::class.java).refresh()

        // 코퍼스를 새 버전 인덱스에 색인하고 alias로 승격(reindexAll과 동일 경로) → 검색 대상 확정.
        fun seed(vararg boards: Board) {
            val version = boardSearchAdapter.createNewVersionIndex()
            boardSearchAdapter.indexInto(boards.toList(), version)
            boardSearchAdapter.promote(version)
            refresh()
        }

        fun b(
            id: Long,
            title: String,
            content: String,
        ) = Board(id = id, title = title, content = content, createdAt = LocalDateTime.now())

        Given("한글 코퍼스가 색인돼 있을 때 - 검색 품질 회귀") {
            seed(
                b(7001L, "카카오메일 스팸 필터 개선", "스팸 차단 정책을 업데이트했습니다."),
                b(7002L, "주소록 동기화 안내", "연락처가 메일과 함께 동기화됩니다."),
                b(7003L, "휴가 신청 방법", "인사팀에 문의하세요. 메일과 무관한 공지입니다."),
                b(7004L, "사내 체육대회 공지", "다음 주 금요일에 진행합니다."),
                b(7005L, "메일 용량 확대 안내", "메일함 용량이 늘어납니다. 메일 첨부 한도도 상향."),
            )

            When("[REQ-1] 복합어 '카카오메일'이 '메일'로 검색되는가 (Nori decompound)") {
                val ids = boardSearchAdapter.search("메일", 10).map { it.board.id }
                Then("제목 복합어가 형태소 분해돼 매칭된다") {
                    ids shouldContain 7001L
                }
            }

            When("[REQ-2] 본문의 조사 결합형 '메일과'가 '메일'로 검색되는가 (조사 분리)") {
                val ids = boardSearchAdapter.search("메일", 10).map { it.board.id }
                Then("조사가 분리돼 본문 매칭된다") {
                    ids shouldContain 7002L
                    ids shouldContain 7003L
                }
            }

            When("[REQ-3] 제목 매칭이 본문 매칭보다 상위인가 (title^2 가중치)") {
                val hits = boardSearchAdapter.search("메일", 10)
                Then("'메일'이 제목+본문에 여러 번 나오는 7005가 최상위, 본문에만 있는 문서보다 앞선다") {
                    hits.first().board.id shouldBe 7005L
                    val rank5005 = hits.indexOfFirst { it.board.id == 7005L }
                    val rank7003 = hits.indexOfFirst { it.board.id == 7003L } // 본문에만 '메일과'
                    (rank5005 < rank7003) shouldBe true
                }
            }

            When("[REQ-4] 무관한 문서는 결과에서 제외되는가 (precision)") {
                val ids = boardSearchAdapter.search("메일", 10).map { it.board.id }
                Then("'메일'이 전혀 없는 체육대회 공지(7004)는 결과에 없다") {
                    ids shouldNotContain 7004L
                }
            }

            When("[REQ-5] 매칭 부분이 하이라이트되는가 (<em>)") {
                val hit = boardSearchAdapter.search("메일", 10).first { it.board.id == 7005L }
                Then("제목 하이라이트에 <em>메일</em>이 포함된다") {
                    (hit.highlightedTitle ?: "").stringShouldContain("<em>메일</em>")
                }
            }

            When("[REQ-6] 다른 요구사항(검색어 '스팸')도 회귀로 함께 유지되는가") {
                val ids = boardSearchAdapter.search("스팸", 10).map { it.board.id }
                Then("'스팸'은 7001만 매칭한다(제목+본문), 다른 문서는 아니다") {
                    ids shouldContain 7001L
                    ids shouldNotContain 7005L
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
