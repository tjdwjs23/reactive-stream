package demo.board.adapter.out.redis

import demo.board.application.port.out.DistributedLockPort
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration

// 실제 Redis(Testcontainers)에 붙여 분산 락(SET NX PX + compare-and-delete 해제)의 획득/스킵/재획득을 검증합니다.
// 전체 앱 컨텍스트를 띄우므로(스키마 초기화 때문에 Postgres도 필요) TestContainers.registerAll로 의존 컨테이너를 등록합니다.
@SpringBootTest
class RedisDistributedLockAdapterTest(
    private val lock: DistributedLockPort,
    private val redis: StringRedisTemplate,
) : BehaviorSpec({

        val key = "test:lock:flush"

        fun clean() {
            redis.delete(key)
        }

        Given("아무도 락을 잡고 있지 않을 때") {
            When("withLock을 호출하면") {
                Then("블록을 실행하고 그 결과를 반환한다") {
                    clean()
                    lock.withLock(key, Duration.ofSeconds(10)) { "ran" } shouldBe "ran"
                }

                Then("실행이 끝나면 락이 해제돼 곧바로 다시 획득할 수 있다") {
                    clean()
                    lock.withLock(key, Duration.ofSeconds(10)) { 1 } shouldBe 1
                    lock.withLock(key, Duration.ofSeconds(10)) { 2 } shouldBe 2
                }
            }
        }

        Given("이미 다른 홀더가 같은 키를 점유 중일 때(비대기)") {
            When("점유 중에 같은 키로 다시 withLock을 시도하면") {
                Then("안쪽 블록을 실행하지 않고 null을 반환한다") {
                    clean()
                    var innerRan = false
                    val outer =
                        lock.withLock(key, Duration.ofSeconds(30)) {
                            // 바깥 락을 쥔 채 같은 키를 다시 시도 → 획득 실패(null), 안쪽 블록 미실행
                            val nested =
                                lock.withLock(key, Duration.ofSeconds(30)) {
                                    innerRan = true
                                    "inner"
                                }
                            "outer-ran:nested=$nested"
                        }
                    outer shouldBe "outer-ran:nested=null"
                    innerRan shouldBe false
                }
            }
        }

        Given("홀더가 락을 쥔 채 죽어 TTL로만 풀리는 상황") {
            When("짧은 TTL로 선점된 뒤 만료를 기다렸다가 다시 시도하면") {
                Then("만료 전엔 못 잡고(null), 만료 후엔 새로 획득한다") {
                    clean()
                    // 다른 홀더가 200ms TTL로 선점(해제 코드 없이 죽은 상황을 모사)
                    redis.opsForValue().setIfAbsent(key, "someone-else", Duration.ofMillis(200))

                    // 아직 점유 중 → 획득 실패
                    lock.withLock(key, Duration.ofSeconds(5)) { "x" } shouldBe null

                    // TTL 경과를 기다리면 자동 해제 → 재획득 성공
                    delay(400)
                    lock.withLock(key, Duration.ofSeconds(5)) { "y" } shouldBe "y"
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
