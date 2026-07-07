package demo.board.adapter.out.persistence

import demo.board.application.port.out.BoardEventOutboxPort
import demo.board.application.port.out.OutboxRecord
import demo.board.application.port.out.OutboxRelayPort
import demo.board.events.BoardChangedEvent
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

// 아웃박스 테이블(board_outbox)의 R2DBC 어댑터. 기록(BoardEventOutboxPort)과 릴레이 조회/표시(OutboxRelayPort)를
// 한 어댑터로 구현합니다 — 둘 다 같은 테이블을 다루기 때문입니다.
//
// record()는 반드시 게시글 쓰기와 "같은 트랜잭션"에서 호출됩니다(TransactionRunnerPort가 경계를 만듦).
// payload는 이벤트를 JSON으로 직렬화한 원문이며, 릴레이는 이를 그대로 재발행합니다(직렬화 지식은 어댑터에만).
@Repository
class OutboxPersistenceAdapter(
    private val databaseClient: DatabaseClient,
    private val objectMapper: ObjectMapper,
) : BoardEventOutboxPort,
    OutboxRelayPort {
    override suspend fun record(event: BoardChangedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        databaseClient
            .sql(
                "INSERT INTO board_outbox (event_id, aggregate_id, event_type, partition_key, payload) " +
                    "VALUES (:eventId, :aggregateId, :eventType, :partitionKey, :payload)",
            ).bind("eventId", event.eventId)
            .bind("aggregateId", event.boardId)
            .bind("eventType", event.type.name)
            .bind("partitionKey", event.boardId.toString())
            .bind("payload", payload)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // 미발행 레코드를 id 오름차순(기록 순서)으로 limit건. 부분 인덱스(idx_board_outbox_unpublished)가 뒷받침합니다.
    override suspend fun readUnpublished(limit: Int): List<OutboxRecord> =
        databaseClient
            .sql(
                "SELECT id, partition_key, payload FROM board_outbox " +
                    "WHERE published_at IS NULL ORDER BY id ASC LIMIT :limit",
            ).bind("limit", limit)
            .map { row ->
                OutboxRecord(
                    id = row.get("id", java.lang.Long::class.java)!!.toLong(),
                    partitionKey = row.get("partition_key", String::class.java)!!,
                    payload = row.get("payload", String::class.java)!!,
                )
            }.all()
            .collectList()
            .awaitSingle()

    // 미발행 백로그 총수. 부분 인덱스(idx_board_outbox_unpublished)만 스캔하므로 미발행분이 적을수록 저렴합니다.
    override suspend fun countUnpublished(): Long =
        databaseClient
            .sql("SELECT count(*) AS backlog FROM board_outbox WHERE published_at IS NULL")
            .map { row -> row.get("backlog", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    // 발행 완료 표시. IN 목록 대신 = ANY(배열) 바인딩을 씁니다(addViewCountsBatch의 unnest와 같은 이유 —
    // DatabaseClient의 컬렉션 확장에 기대지 않고 배열 파라미터로 안전하게 넘김).
    override suspend fun markPublished(ids: List<Long>) {
        if (ids.isEmpty()) return
        databaseClient
            .sql("UPDATE board_outbox SET published_at = now() WHERE id = ANY(:ids)")
            .bind("ids", ids.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
