package demo.reactivestream.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDateTime

// CoroutineCrudRepository: save/findById/findAll/deleteById 등 기본 CRUD가 suspend/Flow 시그니처로 제공됩니다.
// R2DBC 쿼리는 JPQL이 아닌 네이티브 SQL이며, 컬럼명도 실제 테이블 스키마(snake_case)를 그대로 씁니다.
interface BoardR2dbcRepository : CoroutineCrudRepository<BoardR2dbcEntity, Long> {
    // 목록 첫 페이지: 최신(id 내림차순)부터 limit건.
    @Query("SELECT * FROM board ORDER BY id DESC LIMIT :limit")
    fun findFirstPage(limit: Int): Flow<BoardR2dbcEntity>

    // 목록 다음 페이지: cursor(마지막으로 본 id) 이전(과거) 데이터를 id 내림차순으로 limit건.
    // 키셋 방식이라 OFFSET 없이도 뒤쪽 페이지 성능이 일정합니다.
    @Query("SELECT * FROM board WHERE id < :cursor ORDER BY id DESC LIMIT :limit")
    fun findPageAfter(
        cursor: Long,
        limit: Int,
    ): Flow<BoardR2dbcEntity>

    // 키셋(seek) 페이지네이션: OFFSET 대신 "마지막으로 읽은 id 이후"를 조건으로 다음 페이지를 읽습니다.
    // OFFSET 방식과 달리 뒤쪽 페이지에서도 성능이 일정해 대용량 스캔에 적합합니다.
    // 한 페이지를 Flow로 흘려보내며, 논블로킹 스트림이라 결과를 통째로 메모리에 올리지 않습니다.
    @Query(
        "SELECT * FROM board " +
            "WHERE created_at < :before AND id > :lastId " +
            "ORDER BY id ASC LIMIT :limit",
    )
    fun findStalePage(
        before: LocalDateTime,
        lastId: Long,
        limit: Int,
    ): Flow<BoardR2dbcEntity>

    // 청크 벌크 삭제: 단일 DELETE 문으로 처리(건별 삭제 대비 DB 왕복 부담 감소).
    // 반환값은 영향받은 행 수. IN (:ids)는 컬렉션 파라미터로 확장됩니다.
    @Modifying
    @Query("DELETE FROM board WHERE id IN (:ids)")
    suspend fun deleteByIdIn(ids: List<Long>): Int

    // 조회수 write-back: DB에서 원자적으로 누적(read-modify-write가 아닌 단일 UPDATE).
    @Modifying
    @Query("UPDATE board SET view_count = view_count + :delta WHERE id = :id")
    suspend fun addViewCount(
        id: Long,
        delta: Long,
    ): Int
}
