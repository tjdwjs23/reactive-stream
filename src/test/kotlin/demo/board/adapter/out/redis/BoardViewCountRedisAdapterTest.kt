package demo.board.adapter.out.redis

import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

// 실제 Redis(Testcontainers)에 붙여 조회수 버퍼의 증가/드레인 동작을 검증합니다.
// 전체 앱 컨텍스트를 띄우므로(스키마 초기화 때문에 Postgres도 필요) TestContainers.registerAll로 둘 다 등록합니다.
@SpringBootTest
class BoardViewCountRedisAdapterTest(
    private val adapter: BoardViewCountRedisAdapter,
    private val redis: ReactiveStringRedisTemplate,
) : BehaviorSpec({

        // 공유 Redis라 각 시나리오 시작 시 관련 키를 비웁니다.
        suspend fun clean() {
            redis.delete("board:views:pending", "board:views:draining").awaitSingleOrNull()
        }

        Given("같은 게시글을 여러 번 조회할 때") {
            When("increment를 반복 호출하면") {
                Then("델타가 누적된다") {
                    clean()
                    adapter.increment(100L)
                    adapter.increment(100L)
                    adapter.increment(100L) shouldBe 3L
                }
            }
        }

        Given("여러 게시글의 델타가 쌓여 있을 때") {
            When("drainPendingDeltas를 호출하면") {
                Then("모든 델타를 반환하고 버퍼를 비운다") {
                    clean()
                    adapter.increment(200L)
                    adapter.increment(200L)
                    adapter.increment(201L)

                    adapter.drainPendingDeltas() shouldBe mapOf(200L to 2L, 201L to 1L)
                    // 드레인 후에는 비어 있어야 한다
                    adapter.drainPendingDeltas() shouldBe emptyMap()
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
