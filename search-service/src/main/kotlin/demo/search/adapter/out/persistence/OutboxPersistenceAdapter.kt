package demo.search.adapter.out.persistence

import demo.search.application.port.out.BoardEventOutboxPort
import demo.search.application.port.out.OutboxRecord
import demo.search.application.port.out.OutboxRelayPort
import demo.search.events.BoardChangedEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

// 아웃박스 테이블(board_outbox)의 어댑터. 기록(BoardEventOutboxPort)과 릴레이 조회/표시(OutboxRelayPort)를
// 한 어댑터로 구현합니다 — 둘 다 같은 테이블을 다루기 때문입니다. JPA 엔티티로 매핑하지 않고 JdbcTemplate 네이티브
// SQL로 다룹니다(벌크 INSERT·부분 인덱스 폴링·배열 바인딩이 JPQL보다 직접적이고, Flyway가 스키마를 소유).
//
// record()는 반드시 게시글 쓰기와 "같은 트랜잭션"에서 호출됩니다(TransactionRunnerPort가 경계를 만들고,
// JdbcTemplate은 그 트랜잭션의 커넥션을 공유). payload는 이벤트를 JSON으로 직렬화한 원문이며, 릴레이는 그대로 재발행합니다.
@Repository
class OutboxPersistenceAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : BoardEventOutboxPort,
    OutboxRelayPort {
    private val insertSql =
        "INSERT INTO board_outbox (event_id, aggregate_id, event_type, partition_key, payload) " +
            "VALUES (?, ?, ?, ?, ?)"

    override fun record(event: BoardChangedEvent) {
        jdbcTemplate.update(
            insertSql,
            event.eventId,
            event.boardId,
            event.type.name,
            event.boardId.toString(),
            objectMapper.writeValueAsString(event),
        )
    }

    // 다건 벌크 기록. JDBC 배치(batchUpdate)로 한 트랜잭션에서 원자적으로 여러 행을 INSERT합니다
    // (아카이브 삭제/조회수 플러시처럼 한 트랜잭션에서 다건 이벤트를 남기는 배치 경로).
    override fun recordAll(events: List<BoardChangedEvent>) {
        if (events.isEmpty()) return
        val batchArgs =
            events.map { event ->
                arrayOf<Any>(
                    event.eventId,
                    event.boardId,
                    event.type.name,
                    event.boardId.toString(),
                    objectMapper.writeValueAsString(event),
                )
            }
        jdbcTemplate.batchUpdate(insertSql, batchArgs)
    }

    // 미발행 레코드를 id 오름차순(기록 순서)으로 limit건. 부분 인덱스(idx_board_outbox_unpublished)가 뒷받침합니다.
    override fun readUnpublished(limit: Int): List<OutboxRecord> =
        jdbcTemplate.query(
            "SELECT id, partition_key, payload FROM board_outbox " +
                "WHERE published_at IS NULL ORDER BY id ASC LIMIT ?",
            PreparedStatementSetter { ps -> ps.setInt(1, limit) },
            RowMapper { rs, _ ->
                OutboxRecord(
                    id = rs.getLong("id"),
                    partitionKey = rs.getString("partition_key"),
                    payload = rs.getString("payload"),
                )
            },
        )

    // 미발행 백로그 총수. 부분 인덱스만 스캔하므로 미발행분이 적을수록 저렴합니다.
    override fun countUnpublished(): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM board_outbox WHERE published_at IS NULL",
            Long::class.java,
        ) ?: 0L

    // 발행 완료 표시. IN 목록 대신 = ANY(배열) 바인딩을 씁니다(배치 경로와 같은 이유).
    override fun markPublished(ids: List<Long>) {
        if (ids.isEmpty()) return
        jdbcTemplate.update(
            "UPDATE board_outbox SET published_at = now() WHERE id = ANY(?)",
            PreparedStatementSetter { ps ->
                ps.setArray(1, ps.connection.createArrayOf("bigint", ids.toTypedArray()))
            },
        )
    }
}
