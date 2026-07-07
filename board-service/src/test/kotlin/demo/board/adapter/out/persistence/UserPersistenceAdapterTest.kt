package demo.board.adapter.out.persistence

import demo.board.domain.model.Role
import demo.board.domain.model.User
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDateTime

// 실제 PostgreSQL(Testcontainers)에 붙어 사용자 영속화를 검증합니다. 컨테이너를 공유하므로
// UNIQUE(username) 충돌을 피하려 시나리오마다 고유한 username을 씁니다.
@SpringBootTest
class UserPersistenceAdapterTest(
    @Autowired private val adapter: UserPersistenceAdapter,
) : BehaviorSpec({

        Given("새 사용자가 주어졌을 때") {
            val user =
                User(
                    username = "persist-user-1",
                    passwordHash = "hashed",
                    role = Role.USER,
                    createdAt = LocalDateTime.now(),
                )

            When("save를 호출하면") {
                val saved = adapter.save(user)

                Then("id가 채번되고 값이 보존된다") {
                    saved.id.shouldNotBeNull()
                    saved.username shouldBe "persist-user-1"
                    saved.role shouldBe Role.USER
                }
            }
        }

        Given("ADMIN 역할 사용자가 저장되어 있을 때") {
            adapter.save(
                User(
                    username = "persist-admin-1",
                    passwordHash = "hashed",
                    role = Role.ADMIN,
                    createdAt = LocalDateTime.now(),
                ),
            )

            When("findByUsername으로 조회하면") {
                val found = adapter.findByUsername("persist-admin-1")

                Then("역할까지 정확히 복원된다") {
                    found.shouldNotBeNull()
                    found?.role shouldBe Role.ADMIN
                }
            }

            When("existsByUsername을 호출하면") {
                Then("존재하는 username은 true, 없는 username은 false다") {
                    adapter.existsByUsername("persist-admin-1") shouldBe true
                    adapter.existsByUsername("no-such-user-xyz") shouldBe false
                }
            }
        }

        Given("존재하지 않는 username으로 조회할 때") {
            When("findByUsername을 호출하면") {
                Then("null을 반환한다") {
                    adapter.findByUsername("absent-user-zzz").shouldBeNull()
                }
            }
        }

        Given("저장된 사용자의 id로 조회할 때 - findById") {
            val saved =
                adapter.save(
                    User(
                        username = "find-by-id-user",
                        passwordHash = "hashed",
                        role = Role.USER,
                        createdAt = LocalDateTime.now(),
                    ),
                )

            When("findById를 호출하면") {
                Then("해당 사용자를 복원하고, 없는 id는 null을 반환한다") {
                    adapter.findById(saved.id!!)!!.username shouldBe "find-by-id-user"
                    adapter.findById(-9999L).shouldBeNull()
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
