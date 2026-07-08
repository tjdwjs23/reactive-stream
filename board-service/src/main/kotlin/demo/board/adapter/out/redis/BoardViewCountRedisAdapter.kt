package demo.board.adapter.out.redis

import demo.board.application.port.out.BoardViewCountPort
import demo.board.config.ResilienceConfig
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

// BoardViewCountPort의 Redis 구현. MVC 스택이라 블로킹 StringRedisTemplate(Lettuce)로 명령을 실행합니다
// (가상 스레드 위에서 돌아 블로킹이 플랫폼 스레드를 점유하지 않음).
//
// 자료구조: Redis Hash 하나(PENDING)에 field=게시글 id, value=누적 델타.
// - 조회: HINCRBY (원자적 증가) — 조회마다 DB를 때리지 않습니다.
// - 플러시(commit-then-delete):
//     (1) snapshotPendingDeltas — PENDING을 DRAINING으로 RENAME(원자적 스냅샷)한 뒤 읽어서 반환.
//         삭제는 여기서 하지 않습니다. RENAME 직후 들어오는 조회는 새로 생기는 PENDING에 쌓입니다.
//     (2) 서비스가 DB에 반영한 뒤 removeDrained로 반영 성공분만 DRAINING에서 지웁니다.
//   반영 전에 죽으면 DRAINING이 남아, 다음 플러시가 그 잔여분을 먼저 재시도합니다(유실 없음, at-least-once).
//   플러시 전체 직렬화는 호출자(FlushBoardViewCountsService)가 분산 락(Redis SET NX)으로 클러스터 전역에서 보장합니다.
@Repository
class BoardViewCountRedisAdapter(
    private val redis: StringRedisTemplate,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : BoardViewCountPort {
    private companion object {
        const val PENDING = "board:views:pending"
        const val DRAINING = "board:views:draining"
    }

    // 조회수 증가(hot read path)에만 서킷브레이커를 겁니다. Redis가 반복 실패/지연하면 서킷이 열려 즉시 실패하고,
    // BoardService가 그 예외를 삼켜 DB 값으로 강등 응답합니다(조회 자체는 계속 성공). 플러시 경로(snapshot/remove)는
    // 분산 락으로 보호되는 관리 작업이라 여기 브레이커 대상에서 제외합니다.
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.REDIS_VIEW_COUNT)

    override fun increment(boardId: Long): Long =
        breaker.executeSupplier {
            redis.opsForHash<String, String>().increment(PENDING, boardId.toString(), 1)
        }

    override fun snapshotPendingDeltas(): Map<Long, Long> {
        // 직전 플러시가 DB 반영 도중 죽어 남긴 스냅샷(DRAINING)이 있으면 그것부터 재시도합니다.
        if (redis.hasKey(DRAINING)) return readDraining()

        // 잔여가 없을 때만 PENDING을 DRAINING으로 원자적으로 옮깁니다(스냅샷). 삭제는 removeDrained가 담당.
        // (비어 있으면 RENAME이 에러를 내므로 먼저 존재 여부를 확인합니다.)
        if (!redis.hasKey(PENDING)) return emptyMap()
        redis.rename(PENDING, DRAINING)
        return readDraining()
    }

    override fun removeDrained(boardIds: Collection<Long>) {
        if (boardIds.isEmpty()) return
        val fields = boardIds.map { it.toString() as Any }.toTypedArray()
        redis.opsForHash<String, String>().delete(DRAINING, *fields)
    }

    private fun readDraining(): Map<Long, Long> =
        redis
            .opsForHash<String, String>()
            .entries(DRAINING)
            .entries
            .associate { it.key.toLong() to it.value.toLong() }
}
