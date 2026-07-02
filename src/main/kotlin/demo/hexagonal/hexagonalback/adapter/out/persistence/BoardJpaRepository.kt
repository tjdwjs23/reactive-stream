package demo.hexagonal.hexagonalback.adapter.out.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface BoardJpaRepository : JpaRepository<BoardJpaEntity, Long> {
    // 키셋(seek) 페이지네이션: OFFSET 대신 "마지막으로 읽은 id 이후"를 조건으로 다음 페이지를 읽습니다.
    // OFFSET 방식과 달리 뒤쪽 페이지에서도 성능이 일정해 대용량 스캔에 적합합니다.
    @Query(
        "SELECT b FROM BoardJpaEntity b " +
            "WHERE b.createdAt < :before AND b.id > :lastId " +
            "ORDER BY b.id ASC",
    )
    fun findStalePage(
        before: LocalDateTime,
        lastId: Long,
        pageable: Pageable,
    ): List<BoardJpaEntity>

    // 청크 벌크 삭제: 단일 DELETE 문으로 처리(건별 삭제 대비 왕복/영속성 컨텍스트 부담 감소).
    @Modifying
    @Query("DELETE FROM BoardJpaEntity b WHERE b.id IN :ids")
    fun deleteByIdIn(ids: List<Long>): Int
}
