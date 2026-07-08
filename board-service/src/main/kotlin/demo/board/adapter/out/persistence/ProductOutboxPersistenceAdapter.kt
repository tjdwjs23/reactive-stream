package demo.board.adapter.out.persistence

import demo.board.application.port.out.OutboxRecord
import demo.board.application.port.out.ProductEventOutboxPort
import demo.board.application.port.out.ProductOutboxRelayPort
import demo.board.events.ProductChangedEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

// product_outbox 어댑터. 기록(ProductEventOutboxPort)과 릴레이 조회/표시(ProductOutboxRelayPort)를 한 어댑터로 구현합니다
// (OutboxPersistenceAdapter[board]와 동형, 테이블만 product_outbox). record()는 상품 쓰기와 "같은 트랜잭션"에서 호출됩니다.
@Repository
class ProductOutboxPersistenceAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ProductEventOutboxPort,
    ProductOutboxRelayPort {
    private val insertSql =
        "INSERT INTO product_outbox (event_id, aggregate_id, event_type, partition_key, payload) " +
            "VALUES (?, ?, ?, ?, ?)"

    override fun record(event: ProductChangedEvent) {
        jdbcTemplate.update(
            insertSql,
            event.eventId,
            event.productId,
            event.type.name,
            event.productId.toString(),
            objectMapper.writeValueAsString(event),
        )
    }

    override fun readUnpublished(limit: Int): List<OutboxRecord> =
        jdbcTemplate.query(
            "SELECT id, partition_key, payload FROM product_outbox " +
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

    override fun countUnpublished(): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM product_outbox WHERE published_at IS NULL",
            Long::class.java,
        ) ?: 0L

    override fun markPublished(ids: List<Long>) {
        if (ids.isEmpty()) return
        jdbcTemplate.update(
            "UPDATE product_outbox SET published_at = now() WHERE id = ANY(?)",
            PreparedStatementSetter { ps ->
                ps.setArray(1, ps.connection.createArrayOf("bigint", ids.toTypedArray()))
            },
        )
    }
}
