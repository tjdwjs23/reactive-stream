package demo.board.application.port.out

import java.time.Duration

// 여러 인스턴스(k8s 다중 레플리카) 간 상호배제를 위한 out-port입니다.
// 조회수 플러시처럼 "클러스터 전역에서 동시에 하나만" 돌아야 하는 흐름을 직렬화합니다.
// 구체 기술(Redis SET NX 등)은 어댑터가 감춥니다 — 서비스는 락의 존재만 알고 Redis를 모릅니다.
interface DistributedLockPort {
    // key에 대한 락을 시도하고, 획득하면 block을 실행해 그 결과를 반환합니다.
    // 이미 다른 홀더가 점유 중이면 block을 실행하지 않고 null을 반환합니다(비대기 tryLock — 대기하지 않고 즉시 포기).
    // ttl은 홀더가 락을 쥔 채 죽어도 락이 영구 점유되지 않도록 하는 안전장치입니다(최대 작업 소요보다 넉넉히).
    suspend fun <T> withLock(
        key: String,
        ttl: Duration,
        block: suspend () -> T,
    ): T?
}
