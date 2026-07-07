package demo.board.application.port.out

// 아웃박스 릴레이가 "미발행 이벤트를 읽고, 발행 완료를 표시"하기 위한 out-port.
// 저장 매체(테이블)는 어댑터가 감춥니다. 릴레이 서비스는 이 포트와 EventPublisherPort만으로 동작합니다.
interface OutboxRelayPort {
    // 미발행 레코드를 id 오름차순(기록 순서)으로 최대 limit건 읽습니다.
    // 순서대로 발행해 같은 게시글의 이벤트 순서가 뒤바뀌지 않게 합니다.
    suspend fun readUnpublished(limit: Int): List<OutboxRecord>

    // 발행에 성공한 레코드들을 발행 완료로 표시합니다(published_at 기록). 성공분만 넘어옵니다.
    suspend fun markPublished(ids: List<Long>)

    // 미발행(백로그) 이벤트 총수. 릴레이가 죽거나 발행이 밀리면 이 값이 계속 커지므로, 파이프라인 건강의 핵심 SLI입니다.
    // 관측성 게이지(board.outbox.unpublished)로 노출됩니다. 부분 인덱스(idx_board_outbox_unpublished)가 뒷받침합니다.
    suspend fun countUnpublished(): Long
}

// 아웃박스 레코드 한 건(발행에 필요한 최소 정보).
// payload는 직렬화된 이벤트 원문(불투명 바이트)이며, 릴레이는 이를 해석하지 않고 그대로 재발행합니다.
data class OutboxRecord(
    val id: Long,
    val partitionKey: String,
    val payload: String,
)
