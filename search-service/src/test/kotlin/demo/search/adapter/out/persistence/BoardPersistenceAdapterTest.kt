package demo.search.adapter.out.persistence

import demo.search.domain.model.Board
import demo.search.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime

@SpringBootTest
class BoardPersistenceAdapterTest(
    @Autowired private val boardPersistenceAdapter: BoardPersistenceAdapter,
) : BehaviorSpec({

        Given("유효한 Board가 주어졌을 때") {
            When("save를 호출하면") {
                val saved =
                    boardPersistenceAdapter.save(
                        Board(title = "제목", content = "내용", createdAt = LocalDateTime.now()),
                    )

                Then("id가 채번된 Board가 저장된다") {
                    saved.id.shouldNotBeNull()
                    saved.title shouldBe "제목"
                    saved.content shouldBe "내용"
                }
            }
        }

        Given("저장된 Board가 존재할 때") {
            val saved =
                boardPersistenceAdapter.save(
                    Board(title = "조회용 제목", content = "조회용 내용", createdAt = LocalDateTime.now()),
                )

            When("findById를 호출하면") {
                val found = boardPersistenceAdapter.findById(saved.id!!)

                Then("저장된 Board를 반환한다") {
                    found.shouldNotBeNull()
                    found.title shouldBe "조회용 제목"
                }
            }

            When("존재하지 않는 id로 findById를 호출하면") {
                val found = boardPersistenceAdapter.findById(-1L)

                Then("null을 반환한다") {
                    found.shouldBeNull()
                }
            }
        }

        Given("여러 Board가 저장되어 있을 때 - 키셋 페이지네이션") {
            val ids =
                (1..5).map {
                    boardPersistenceAdapter
                        .save(
                            Board(title = "page-$it", content = "내용", createdAt = LocalDateTime.now()),
                        ).id!!
                }
            val idSet = ids.toSet()

            When("cursor를 따라가며 pageSize=2로 findPage를 반복 조회하면") {
                // 다른 스펙이 넣은 데이터와 컨테이너를 공유하므로, 우리 id만 모아 검증합니다.
                val collected = mutableListOf<Long>()
                var maxPageSize = 0
                var cursor: Long? = null
                var guard = 0
                while (guard++ < 10_000) {
                    val page = boardPersistenceAdapter.findPage(cursor, 2).toList()
                    if (page.isEmpty()) break
                    maxPageSize = maxOf(maxPageSize, page.size)
                    page.forEach { if (it.id in idSet) collected.add(it.id!!) }
                    cursor = page.last().id
                }

                Then("우리 id가 id 내림차순으로 모두 반환된다") {
                    collected shouldContainExactly ids.sortedDescending()
                }

                Then("각 페이지는 pageSize를 넘지 않는다") {
                    (maxPageSize <= 2) shouldBe true
                }
            }
        }

        Given("여러 Board가 저장되어 있을 때 - 조회수 배치 write-back") {
            val a =
                boardPersistenceAdapter
                    .save(
                        Board(title = "배치A", content = "내용", createdAt = LocalDateTime.now()),
                    ).id!!
            val b =
                boardPersistenceAdapter
                    .save(
                        Board(title = "배치B", content = "내용", createdAt = LocalDateTime.now()),
                    ).id!!

            When("addViewCountsBatch로 여러 게시글 델타를 한 번에 반영하면") {
                val updated = boardPersistenceAdapter.addViewCountsBatch(mapOf(a to 7L, b to 3L, -1L to 99L))

                Then("존재하는 게시글의 view_count만 델타만큼 증가하고, RETURNING으로 반영-후 상태를 돌려준다") {
                    // 존재하지 않는 -1L은 반영되지 않아 결과에서 제외된다
                    updated.map { it.id } shouldContainExactlyInAnyOrder listOf(a, b)
                    // RETURNING이 반영 직후 누적 view_count를 담는다(재조회 없이 이벤트 페이로드 구성 가능)
                    updated.first { it.id == a }.viewCount shouldBe 7L
                    updated.first { it.id == b }.viewCount shouldBe 3L
                    boardPersistenceAdapter.findById(a)?.viewCount shouldBe 7L
                    boardPersistenceAdapter.findById(b)?.viewCount shouldBe 3L
                }
            }
        }

        Given("저장된 Board가 존재할 때") {
            val saved =
                boardPersistenceAdapter.save(
                    Board(title = "삭제될 제목", content = "삭제될 내용", createdAt = LocalDateTime.now()),
                )

            When("deleteById를 호출하면") {
                boardPersistenceAdapter.deleteById(saved.id!!)

                Then("더 이상 조회되지 않는다") {
                    boardPersistenceAdapter.findById(saved.id).shouldBeNull()
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
