package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.domain.model.Board
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
class BoardPersistenceAdapterTest(
    @Autowired private val boardPersistenceAdapter: BoardPersistenceAdapter,
) : BehaviorSpec({

        Given("유효한 Board가 주어졌을 때") {
            When("save를 호출하면") {
                val saved = boardPersistenceAdapter.save(Board(title = "제목", content = "내용"))

                Then("id가 채번된 Board가 저장된다") {
                    saved.id.shouldNotBeNull()
                    saved.title shouldBe "제목"
                    saved.content shouldBe "내용"
                }
            }
        }

        Given("저장된 Board가 존재할 때") {
            val saved = boardPersistenceAdapter.save(Board(title = "조회용 제목", content = "조회용 내용"))

            When("findById를 호출하면") {
                val found = boardPersistenceAdapter.findById(saved.id!!)

                Then("저장된 Board를 반환한다") {
                    found.shouldNotBeNull()
                    found?.title shouldBe "조회용 제목"
                }
            }

            When("존재하지 않는 id로 findById를 호출하면") {
                val found = boardPersistenceAdapter.findById(-1L)

                Then("null을 반환한다") {
                    found.shouldBeNull()
                }
            }
        }

        Given("여러 Board가 저장되어 있을 때") {
            boardPersistenceAdapter.save(Board(title = "목록1", content = "내용1"))
            boardPersistenceAdapter.save(Board(title = "목록2", content = "내용2"))

            When("findAll을 호출하면") {
                val all = boardPersistenceAdapter.findAll()

                Then("저장된 Board를 모두 포함한 목록을 반환한다") {
                    all.map { it.title } shouldContainAll listOf("목록1", "목록2")
                }
            }
        }

        Given("저장된 Board가 존재할 때") {
            val saved = boardPersistenceAdapter.save(Board(title = "삭제될 제목", content = "삭제될 내용"))

            When("deleteById를 호출하면") {
                boardPersistenceAdapter.deleteById(saved.id!!)

                Then("더 이상 조회되지 않는다") {
                    boardPersistenceAdapter.findById(saved.id!!).shouldBeNull()
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withInitScript("sql/board.sql")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
