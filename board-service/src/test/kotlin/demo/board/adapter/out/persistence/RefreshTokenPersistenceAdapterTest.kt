package demo.board.adapter.out.persistence

import demo.board.domain.model.RefreshToken
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

// 실제 PostgreSQL(Testcontainers)에 붙어 리프레시 토큰 영속화/폐기를 검증합니다.
// token_hash는 UNIQUE라 시나리오마다 고유한 해시를 씁니다.
@SpringBootTest
class RefreshTokenPersistenceAdapterTest(
    @Autowired private val adapter: RefreshTokenPersistenceAdapter,
) : BehaviorSpec({

        fun token(
            userId: Long,
            hash: String,
        ) = RefreshToken(
            userId = userId,
            tokenHash = hash,
            expiresAt = LocalDateTime.now().plusDays(14),
            createdAt = LocalDateTime.now(),
        )

        Given("리프레시 토큰을 저장하면") {
            adapter.save(token(8001L, "hash-8001"))

            When("findByHash로 조회하면") {
                val found = adapter.findByHash("hash-8001")

                Then("id가 채번되고 활성(revokedAt=null) 상태로 복원된다") {
                    found.shouldNotBeNull()
                    found?.id.shouldNotBeNull()
                    found?.userId shouldBe 8001L
                    found?.revokedAt.shouldBeNull()
                }
            }

            When("revoke(id)로 폐기하면") {
                val id = adapter.findByHash("hash-8001")?.id.shouldNotBeNull()
                adapter.revoke(id)

                Then("revokedAt이 채워진다") {
                    adapter.findByHash("hash-8001")?.revokedAt.shouldNotBeNull()
                }
            }
        }

        Given("한 사용자의 활성 토큰이 여러 개일 때") {
            adapter.save(token(8002L, "hash-8002-a"))
            adapter.save(token(8002L, "hash-8002-b"))

            When("revokeAllForUser로 전체 폐기하면") {
                adapter.revokeAllForUser(8002L)

                Then("해당 사용자의 모든 토큰이 폐기된다") {
                    adapter.findByHash("hash-8002-a")?.revokedAt.shouldNotBeNull()
                    adapter.findByHash("hash-8002-b")?.revokedAt.shouldNotBeNull()
                }
            }
        }

        Given("존재하지 않는 해시로 조회하면") {
            When("findByHash를 호출하면") {
                Then("null을 반환한다") {
                    adapter.findByHash("no-such-hash").shouldBeNull()
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
