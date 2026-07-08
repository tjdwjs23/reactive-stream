package demo.board.adapter.out.redis

import demo.board.application.port.out.DistributedLockPort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

// DistributedLockPort의 Redis 구현. 단일 키에 대한 비대기 tryLock을 SET NX PX로 구현합니다(블로킹 Lettuce).
//  - 획득: SET key <token> NX PX <ttl>  (setIfAbsent + timeout). 성공하면 이 인스턴스만 진입합니다.
//  - 해제: "내 토큰일 때만 삭제"하는 Lua(get==token이면 del). TTL을 넘겨 내 락이 만료된 뒤 다른 홀더가 잡은
//         락을 실수로 지우지 않도록 compare-and-delete를 원자적으로 수행합니다.
//  - 미획득: 다른 홀더가 점유 중 → block을 실행하지 않고 null 반환(호출자가 이번 차례를 스킵).
//
// 주의: 단일 Redis 노드 기준의 락입니다. Redis HA(마스터 장애 페일오버 중 락 손실)까지 견뎌야 하면
// Redlock(다중 노드)나 리더 선출을 씁니다. 조회수 플러시는 유실 없는 at-least-once라 이 수준으로 충분합니다.
@Repository
class RedisDistributedLockAdapter(
    private val redis: StringRedisTemplate,
) : DistributedLockPort {
    private val log = LoggerFactory.getLogger(javaClass)

    // 내가 잡은 락만 지우기 위한 compare-and-delete. get==token 검사와 del을 한 Lua로 묶어 원자적으로 처리합니다.
    private val releaseScript =
        DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.javaObjectType,
        )

    override fun <T> withLock(
        key: String,
        ttl: Duration,
        block: () -> T,
    ): T? {
        // 이번 획득에만 유효한 토큰. 해제 시 "내가 잡은 락"인지 식별하는 데 씁니다.
        val token = UUID.randomUUID().toString()
        val acquired = redis.opsForValue().setIfAbsent(key, token, ttl) ?: false // SET NX PX — 이미 있으면 false
        if (!acquired) return null

        try {
            return block()
        } finally {
            // 예외가 나도 락은 반드시 해제합니다(안 하면 TTL 만료까지 클러스터 전역이 막힙니다).
            releaseSafely(key, token)
        }
    }

    private fun releaseSafely(
        key: String,
        token: String,
    ) {
        try {
            redis.execute(releaseScript, listOf(key), token)
        } catch (e: Exception) {
            // 해제에 실패해도 TTL로 결국 풀리고 다음 주기가 재시도하므로, 로그만 남기고 삼킵니다.
            log.warn("failed to release distributed lock (key={}); it will expire via TTL. cause={}", key, e.toString())
        }
    }
}
