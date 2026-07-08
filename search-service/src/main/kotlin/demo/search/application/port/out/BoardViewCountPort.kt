package demo.search.application.port.out

// 조회수 델타를 빠른 저장소(Redis)에 누적하고, 플러시 시 원자적으로 꺼내는 out-port입니다.
// "조회마다 DB UPDATE"를 피하기 위한 write-back 버퍼 역할을 하며, 구체 기술(Redis)은 어댑터가 감춥니다.
interface BoardViewCountPort {
    // 조회 1건을 반영하고, 해당 게시글의 "아직 DB에 반영되지 않은 누적 델타"를 반환합니다.
    fun increment(boardId: Long): Long

    // 반영할 델타의 원자적 스냅샷을 만들어 반환합니다(boardId -> delta).
    // 버퍼를 곧바로 비우지 않고 별도 스냅샷 공간으로 옮겨만 둡니다 — DB 반영에 성공한 만큼만
    // removeDrained로 지우는 commit-then-delete를 위해서입니다(반영 전에 죽어도 유실 없음).
    // 직전 플러시가 DB 반영 도중 죽어 스냅샷이 남아 있으면, 그 잔여분을 먼저 반환해 재시도하게 합니다.
    fun snapshotPendingDeltas(): Map<Long, Long>

    // DB 반영에 성공한 게시글 id들을 스냅샷에서 제거합니다(반영 성공 후에만 호출).
    // 반영과 제거 사이에 죽으면 남아 있어 다음 플러시가 재시도하므로, 유실 대신 중복 계수를 택합니다(at-least-once).
    fun removeDrained(boardIds: Collection<Long>)
}
