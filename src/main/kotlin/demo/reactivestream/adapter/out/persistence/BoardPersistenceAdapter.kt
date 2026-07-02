package demo.reactivestream.adapter.out.persistence

import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.domain.model.Board
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

@Repository // 스프링 빈으로 등록
class BoardPersistenceAdapter(
    private val boardR2dbcRepository: BoardR2dbcRepository,
    private val boardMapper: BoardMapper,
    private val databaseClient: DatabaseClient,
) : BoardRepositoryPort {
    override suspend fun save(board: Board): Board {
        // 1. 도메인을 엔티티로 변환
        val entity = boardMapper.toEntity(board)
        // 2. R2DBC로 저장 (id가 채번된 엔티티가 반환됨)
        val savedEntity = boardR2dbcRepository.save(entity)
        // 3. 다시 도메인으로 변환해서 반환
        return boardMapper.toDomain(savedEntity)
    }

    override suspend fun findById(id: Long): Board? =
        boardR2dbcRepository.findById(id)?.let { boardMapper.toDomain(it) }

    // 키셋 페이지네이션. cursor 유무에 따라 첫 페이지/다음 페이지 쿼리를 고릅니다.
    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): Flow<Board> =
        (
            if (cursor == null) {
                boardR2dbcRepository.findFirstPage(limit)
            } else {
                boardR2dbcRepository.findPageAfter(cursor, limit)
            }
        ).map { boardMapper.toDomain(it) }

    override suspend fun deleteById(id: Long) {
        boardR2dbcRepository.deleteById(id)
    }

    override suspend fun addViewCount(
        boardId: Long,
        delta: Long,
    ): Int = boardR2dbcRepository.addViewCount(boardId, delta)

    // 배치 write-back: unnest(배열)로 (id, delta) 쌍을 펼쳐 단일 UPDATE로 조인 반영합니다.
    // id/delta 배열은 같은 순서로 바인딩되어야 하므로 entries에서 함께 만듭니다(Map 순회 순서 일치에 의존하지 않음).
    override suspend fun addViewCountsBatch(deltas: Map<Long, Long>): Int {
        if (deltas.isEmpty()) return 0
        val entries = deltas.entries.toList()
        val ids = Array(entries.size) { entries[it].key }
        val amounts = Array(entries.size) { entries[it].value }
        return databaseClient
            .sql(
                "UPDATE board AS b SET view_count = b.view_count + d.delta " +
                    "FROM unnest(:ids, :deltas) AS d(id, delta) WHERE b.id = d.id",
            ).bind("ids", ids)
            .bind("deltas", amounts)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
            .toInt()
    }
}
