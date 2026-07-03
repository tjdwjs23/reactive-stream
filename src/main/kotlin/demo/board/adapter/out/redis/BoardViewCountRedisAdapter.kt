package demo.board.adapter.out.redis

import demo.board.application.port.out.BoardViewCountPort
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Repository

// BoardViewCountPort의 Redis 구현. 리액티브(Lettuce) 클라이언트라 모든 명령이 논블로킹입니다.
//
// 자료구조: Redis Hash 하나(PENDING)에 field=게시글 id, value=누적 델타.
// - 조회: HINCRBY (원자적 증가) — 조회마다 DB를 때리지 않습니다.
// - 플러시: PENDING을 DRAINING으로 RENAME(원자적 스냅샷)한 뒤 통째로 읽고 삭제합니다.
//   RENAME 직후 들어오는 조회는 새로 생성되는 PENDING에 쌓이므로 델타가 유실되지 않습니다.
@Repository
class BoardViewCountRedisAdapter(
    private val redis: ReactiveStringRedisTemplate,
) : BoardViewCountPort {
    private companion object {
        const val PENDING = "board:views:pending"
        const val DRAINING = "board:views:draining"
    }

    override suspend fun increment(boardId: Long): Long =
        redis
            .opsForHash<String, String>()
            .increment(PENDING, boardId.toString(), 1)
            .awaitSingle()

    override suspend fun drainPendingDeltas(): Map<Long, Long> {
        // 버퍼가 비어 있으면 rename이 에러를 내므로 먼저 존재 여부를 확인합니다.
        if (redis.hasKey(PENDING).awaitSingle() != true) return emptyMap()

        // 원자적 스냅샷. (직전 플러시가 중간에 죽어 DRAINING이 남아 있으면 이번 rename이 덮어써
        //  그만큼 유실될 수 있습니다 — 학습용 단순화. 실무에선 DRAINING 잔여분을 먼저 처리하거나 커밋 후 삭제합니다.)
        redis.rename(PENDING, DRAINING).awaitSingle()

        val entries =
            redis
                .opsForHash<String, String>()
                .entries(DRAINING)
                .collectList()
                .awaitSingle()

        redis.delete(DRAINING).awaitSingleOrNull()

        return entries.associate { it.key.toLong() to it.value.toLong() }
    }
}
