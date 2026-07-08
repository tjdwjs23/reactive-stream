package demo.search.adapter.out.persistence

import demo.search.application.port.out.BoardBatchQueryPort
import demo.search.domain.model.Board
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

// BoardBatchQueryPort의 구현체. 일반 CRUD용 BoardPersistenceAdapter와 분리해
// 배치 특유의 관심사(키셋 페이지 조회, 벌크 삭제)만 담습니다.
@Repository
class BoardBatchPersistenceAdapter(
    private val boardJpaRepository: BoardJpaRepository,
    private val boardMapper: BoardMapper,
    private val jdbcTemplate: JdbcTemplate,
) : BoardBatchQueryPort {
    // 키셋 페이지네이션 한 페이지(Kotlin JDSL): created_at < before AND id > lastId 를 id 오름차순으로 pageSize건.
    // 스트리밍/백프레셔는 호출자(ArchiveStaleBoardsService)가 이 페이지를 코루틴 Channel로 흘려보내며 구현합니다.
    override fun findStalePage(
        before: LocalDateTime,
        lastId: Long,
        pageSize: Int,
    ): List<Board> =
        boardJpaRepository
            .findAll(limit = pageSize) {
                select(entity(BoardJpaEntity::class))
                    .from(entity(BoardJpaEntity::class))
                    .whereAnd(
                        path(BoardJpaEntity::createdAt).lessThan(before),
                        path(BoardJpaEntity::id).greaterThan(lastId),
                    ).orderBy(path(BoardJpaEntity::id).asc())
            }.filterNotNull()
            .map(boardMapper::toDomain)

    // 청크 벌크 삭제: 단일 DELETE(WHERE id = ANY(?))로 처리하고 삭제된 행 수를 반환합니다(archive 결과 집계용).
    // = ANY(배열)는 IN 목록보다 파라미터 바인딩이 안정적입니다(unnest/배치 UPDATE와 같은 이유).
    override fun deleteByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return jdbcTemplate.update(
            "DELETE FROM board WHERE id = ANY(?)",
            PreparedStatementSetter { ps ->
                ps.setArray(1, ps.connection.createArrayOf("bigint", ids.toTypedArray()))
            },
        )
    }
}
