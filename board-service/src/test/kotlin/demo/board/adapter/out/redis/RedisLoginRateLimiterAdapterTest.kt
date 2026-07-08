package demo.board.adapter.out.redis

import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

// 실제 Redis(Testcontainers)에 붙어 로그인 rate limiting을 검증합니다. 기본 임계치는 application.yml의 max-attempts(5).
@SpringBootTest
class RedisLoginRateLimiterAdapterTest(
    @Autowired private val adapter: RedisLoginRateLimiterAdapter,
    @Autowired private val redis: StringRedisTemplate,
) : BehaviorSpec({

        fun clean(key: String) {
            redis.delete("login:fail:$key")
        }

        Given("한 계정의 로그인 실패가 누적될 때") {
            val key = "rl-user-1"

            When("임계치(5)만큼 실패를 기록하면") {
                Then("임계치 도달 전에는 통과, 도달 시 차단된다") {
                    clean(key)
                    // 4회까지는 아직 차단되지 않는다.
                    repeat(4) { adapter.recordFailure(key) }
                    adapter.isBlocked(key) shouldBe false
                    // 5회째에 임계치 도달 → 차단.
                    adapter.recordFailure(key)
                    adapter.isBlocked(key) shouldBe true
                }
            }

            When("성공(reset) 후에는") {
                Then("카운터가 초기화돼 다시 통과한다") {
                    clean(key)
                    repeat(5) { adapter.recordFailure(key) }
                    adapter.isBlocked(key) shouldBe true
                    adapter.reset(key)
                    adapter.isBlocked(key) shouldBe false
                }
            }
        }

        Given("실패 이력이 없는 계정") {
            When("isBlocked를 조회하면") {
                Then("차단되지 않는다") {
                    clean("never-failed")
                    adapter.isBlocked("never-failed") shouldBe false
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
