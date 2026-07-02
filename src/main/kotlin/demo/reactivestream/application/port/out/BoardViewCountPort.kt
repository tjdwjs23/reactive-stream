package demo.reactivestream.application.port.out

// 조회수 델타를 빠른 저장소(Redis)에 누적하고, 플러시 시 원자적으로 꺼내는 out-port입니다.
// "조회마다 DB UPDATE"를 피하기 위한 write-back 버퍼 역할을 하며, 구체 기술(Redis)은 어댑터가 감춥니다.
interface BoardViewCountPort {
    // 조회 1건을 반영하고, 해당 게시글의 "아직 DB에 반영되지 않은 누적 델타"를 반환합니다.
    suspend fun increment(boardId: Long): Long

    // 지금까지 쌓인 모든 게시글의 델타를 원자적으로 꺼내며 버퍼를 비웁니다(boardId -> delta).
    // 플러시 이후 들어오는 조회는 새 버퍼에 쌓이므로 유실되지 않습니다.
    suspend fun drainPendingDeltas(): Map<Long, Long>
}
