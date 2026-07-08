package demo.board.adapter.out.redis

import demo.board.application.port.out.LoginRateLimiterPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

// LoginRateLimiterPort의 Redis 구현(블로킹 Lettuce). username별 실패 횟수를 고정 윈도우로 셉니다.
//
//  - recordFailure: INCR login:fail:{key}. 값이 1이면(첫 실패) EXPIRE로 윈도우 TTL을 걸어 → 윈도우가 흐르면
//    카운터가 통째로 사라져 자동으로 리셋됩니다(고정 윈도우). 여러 인스턴스가 같은 Redis 키를 공유하므로
//    멀티 파드에서도 계정 단위로 정확히 합산됩니다(인메모리 방식과 달리 파드별로 갈라지지 않음).
//  - isBlocked: 현재 카운터가 maxAttempts 이상이면 차단.
//  - reset: 로그인 성공 시 키 삭제(정상 사용자가 과거 실패로 잠기지 않도록).
@Repository
class RedisLoginRateLimiterAdapter(
    private val redis: StringRedisTemplate,
    @Value("\${board.security.login.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${board.security.login.window-minutes:15}") private val windowMinutes: Long,
) : LoginRateLimiterPort {
    override fun isBlocked(key: String): Boolean {
        val current = redis.opsForValue().get(redisKey(key))?.toIntOrNull() ?: 0
        return current >= maxAttempts
    }

    override fun recordFailure(key: String) {
        val k = redisKey(key)
        val count = redis.opsForValue().increment(k) ?: 0L
        // 첫 실패에만 윈도우 TTL을 건다(이후 INCR은 TTL을 갱신하지 않아 고정 윈도우가 유지됨).
        if (count == 1L) {
            redis.expire(k, Duration.ofMinutes(windowMinutes))
        }
    }

    override fun reset(key: String) {
        redis.delete(redisKey(key))
    }

    private fun redisKey(key: String) = "login:fail:$key"
}
