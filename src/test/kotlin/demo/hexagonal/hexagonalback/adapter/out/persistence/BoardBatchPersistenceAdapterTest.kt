package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.domain.model.Board
import demo.hexagonal.hexagonalback.support.PostgresTestContainer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime

@SpringBootTest
class BoardBatchPersistenceAdapterTest(
    @Autowired private val boardBatchPersistenceAdapter: BoardBatchPersistenceAdapter,
    @Autowired private val boardPersistenceAdapter: BoardPersistenceAdapter,
) : BehaviorSpec({

        val now = LocalDateTime.now()
        val threshold = now.minusDays(365)

        Given("오래된 게시글 5건과 최신 게시글 2건이 저장되어 있을 때") {
            // 최신 게시글: 다른 스펙이 넣은 now 기준 데이터와 함께 threshold보다 이후라 결과에서 제외되어야 함
            boardPersistenceAdapter.save(Board(title = "fresh-1", content = "내용", createdAt = now.minusDays(10)))
            boardPersistenceAdapter.save(Board(title = "fresh-2", content = "내용", createdAt = now.minusDays(1)))

            val staleIds =
                (1..5).map {
                    boardPersistenceAdapter
                        .save(Board(title = "stale-$it", content = "내용", createdAt = now.minusDays(400 + it.toLong())))
                        .id!!
                }

            When("pageSize=2로 findStaleBoards를 스트리밍 조회하면") {
                // 키셋 페이지네이션이 여러 페이지(2+2+1)에 걸쳐 동작하는지 검증
                val collectedStaleIds =
                    boardBatchPersistenceAdapter
                        .findStaleBoards(threshold, pageSize = 2)
                        .map { it.id!! }
                        .filter { it in staleIds }
                        .toList()

                Then("오래된 게시글만 id 오름차순으로 모두 반환한다") {
                    collectedStaleIds shouldContainExactly staleIds.sorted()
                }

                Then("최신 게시글은 결과에 포함되지 않는다") {
                    val allTitles =
                        boardBatchPersistenceAdapter
                            .findStaleBoards(threshold, pageSize = 2)
                            .map { it.title }
                            .toList()
                    allTitles.contains("fresh-1") shouldBe false
                    allTitles.contains("fresh-2") shouldBe false
                }
            }
        }

        Given("오래된 게시글 4건이 저장되어 있을 때") {
            val ids =
                (1..4).map {
                    boardPersistenceAdapter
                        .save(Board(title = "bulk-$it", content = "내용", createdAt = now.minusDays(500 + it.toLong())))
                        .id!!
                }

            When("일부 id로 deleteByIds를 호출하면") {
                val deletedCount = boardBatchPersistenceAdapter.deleteByIds(listOf(ids[0], ids[1]))

                Then("삭제된 행 수를 반환한다") {
                    deletedCount shouldBe 2
                }

                Then("해당 게시글은 삭제되고 나머지는 남는다") {
                    boardPersistenceAdapter.findById(ids[0]).shouldBeNull()
                    boardPersistenceAdapter.findById(ids[1]).shouldBeNull()
                    boardPersistenceAdapter.findById(ids[2]).shouldNotBeNull()
                    boardPersistenceAdapter.findById(ids[3]).shouldNotBeNull()
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = PostgresTestContainer.registerProperties(registry)
    }
}
