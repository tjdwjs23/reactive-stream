package demo.board.adapter.out.redis

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.redis.core.StringRedisTemplate

// 서킷브레이커 open 경로 단위 테스트(컨테이너 불필요). 정상 경로는 BoardViewCountRedisAdapterTest(Testcontainers)가 검증합니다.
class BoardViewCountRedisAdapterCircuitBreakerTest :
    BehaviorSpec({

        Given("redis-viewcount 서킷이 열려 있을 때") {
            val redis = mockk<StringRedisTemplate>()
            val registry = CircuitBreakerRegistry.ofDefaults()
            val adapter = BoardViewCountRedisAdapter(redis, registry)
            registry.circuitBreaker("redis-viewcount").transitionToOpenState()

            When("increment를 호출하면") {
                Then("Redis를 건드리지 않고 즉시 실패한다(CallNotPermittedException) — BoardService가 이를 삼켜 DB 값으로 강등") {
                    shouldThrow<CallNotPermittedException> { adapter.increment(1L) }
                    verify(exactly = 0) { redis.opsForHash<String, String>() }
                }
            }
        }
    })
