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
            When("snapshotPendingDeltas로 스냅샷을 뜨고 removeDrained로 지우면") {
                Then("스냅샷은 모든 델타를 반환하고, 제거 후에는 비어 있다") {
                    clean()
                    adapter.increment(200L)
                    adapter.increment(200L)
                    adapter.increment(201L)

                    val snapshot = adapter.snapshotPendingDeltas()
                    snapshot shouldBe mapOf(200L to 2L, 201L to 1L)

                    // 반영 성공분을 지운 뒤에야 버퍼가 비워진다(commit-then-delete).
                    adapter.removeDrained(snapshot.keys)
                    adapter.snapshotPendingDeltas() shouldBe emptyMap()
                }
            }
        }

        Given("스냅샷을 떴지만 removeDrained 전에 프로세스가 죽은 상황(잔여 DRAINING)") {
            When("다음 플러시가 snapshotPendingDeltas를 다시 호출하면") {
                Then("잔여 스냅샷을 그대로 재시도용으로 반환한다(유실 없음)") {
                    clean()
                    adapter.increment(300L)
                    adapter.increment(301L)
                    // 1차 스냅샷만 뜨고 removeDrained를 호출하지 않아 DRAINING이 남은 상태를 재현
                    adapter.snapshotPendingDeltas() shouldBe mapOf(300L to 1L, 301L to 1L)

                    // 그 사이 새 조회가 들어와도(PENDING에 쌓임) 이번엔 잔여 DRAINING을 먼저 재시도한다
                    adapter.increment(302L)
                    adapter.snapshotPendingDeltas() shouldBe mapOf(300L to 1L, 301L to 1L)

                    // 잔여를 지우면, 그다음 스냅샷이 그동안 쌓인 새 델타를 가져온다
                    adapter.removeDrained(setOf(300L, 301L))
                    adapter.snapshotPendingDeltas() shouldBe mapOf(302L to 1L)
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
