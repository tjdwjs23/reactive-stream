package demo.search.adapter.out.persistence

import demo.search.application.port.out.BoardRepositoryPort
import demo.search.domain.model.Board
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

// BoardRepositoryPort의 JPA 구현. 단건/기본 CRUD는 Spring Data JPA(BoardJpaRepository), 목록은 Kotlin JDSL(키셋),
// 조회수 배치 UPDATE는 PostgreSQL unnest 네이티브 SQL(JdbcTemplate)로 처리합니다.
// JdbcTemplate은 JpaTransactionManager가 바인딩한 커넥션을 그대로 쓰므로, transactionRunner.execute { } 안에서
// JPA 저장과 "같은 트랜잭션"으로 묶입니다(조회수 플러시가 배치 UPDATE + 아웃박스 기록을 원자화하는 경로).
@Repository
class BoardPersistenceAdapter(
    private val boardJpaRepository: BoardJpaRepository,
    private val boardMapper: BoardMapper,
    private val jdbcTemplate: JdbcTemplate,
) : BoardRepositoryPort {
    override fun save(board: Board): Board {
        // id가 null이면 INSERT(IDENTITY 채번), 있으면 merge(UPDATE). IDENTITY라 persist 즉시 id가 채워집니다.
        val saved = boardJpaRepository.save(boardMapper.toEntity(board))
        return boardMapper.toDomain(saved)
    }

    override fun findById(id: Long): Board? = boardJpaRepository.findById(id).orElse(null)?.let(boardMapper::toDomain)

    // 키셋 페이지네이션(Kotlin JDSL). cursor가 있으면 id < cursor 조건을 동적으로 붙이고(whereAnd는 null 술어를 건너뜀),
    // id 내림차순으로 limit건. OFFSET이 없어 뒤쪽 페이지에서도 성능이 일정합니다.
    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Board> =
        boardJpaRepository
            .findAll(limit = limit) {
                select(entity(BoardJpaEntity::class))
                    .from(entity(BoardJpaEntity::class))
                    .whereAnd(cursor?.let { path(BoardJpaEntity::id).lessThan(it) })
                    .orderBy(path(BoardJpaEntity::id).desc())
            }.filterNotNull()
            .map(boardMapper::toDomain)

    override fun deleteById(id: Long) {
        boardJpaRepository.deleteById(id)
    }

    // 배치 write-back: unnest(배열)로 (id, delta) 쌍을 펼쳐 단일 UPDATE로 조인 반영합니다(건별 왕복 제거).
    // RETURNING으로 반영된 행의 최신 상태를 함께 받아, 별도 재조회 없이 UPDATED 이벤트 페이로드를 구성합니다.
    // JPA(Hibernate)가 아닌 JdbcTemplate 네이티브로 실행하지만 같은 트랜잭션/커넥션을 공유합니다.
    override fun addViewCountsBatch(deltas: Map<Long, Long>): List<Board> {
        if (deltas.isEmpty()) return emptyList()
        val entries = deltas.entries.toList()
        val ids = entries.map { it.key }.toTypedArray()
        val amounts = entries.map { it.value }.toTypedArray()
        val sql =
            "UPDATE board AS b SET view_count = b.view_count + d.delta " +
                "FROM unnest(?, ?) AS d(id, delta) WHERE b.id = d.id " +
                "RETURNING b.id, b.title, b.content, b.created_at, b.view_count, b.author_id"
        return jdbcTemplate.query(
            sql,
            PreparedStatementSetter { ps ->
                ps.setArray(1, ps.connection.createArrayOf("bigint", ids))
                ps.setArray(2, ps.connection.createArrayOf("bigint", amounts))
            },
            RowMapper { rs, _ ->
                Board(
                    id = rs.getLong("id"),
                    title = rs.getString("title"),
                    content = rs.getString("content"),
                    createdAt = rs.getObject("created_at", LocalDateTime::class.java),
                    viewCount = rs.getLong("view_count"),
                    authorId = rs.getObject("author_id") as Long?,
                )
            },
        )
    }
}
