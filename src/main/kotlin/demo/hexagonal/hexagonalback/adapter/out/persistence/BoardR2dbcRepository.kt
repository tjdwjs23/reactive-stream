package demo.hexagonal.hexagonalback.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDateTime

// CoroutineCrudRepository: save/findById/findAll/deleteById 등 기본 CRUD가 suspend/Flow 시그니처로 제공됩니다.
// R2DBC 쿼리는 JPQL이 아닌 네이티브 SQL이며, 컬럼명도 실제 테이블 스키마(snake_case)를 그대로 씁니다.
interface BoardR2dbcRepository : CoroutineCrudRepository<BoardR2dbcEntity, Long> {
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
}
