package demo.search.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

// refresh_tokens JPA 리포지토리. 저장/조회는 파생 쿼리, 폐기(UPDATE)는 @Modifying @Query(JPQL)로 명시합니다.
// 폐기 시각(now)은 어댑터가 주입된 Clock에서 만들어 넘깁니다(도메인/서비스가 벽시계를 직접 읽지 않는 원칙과 정합).
// 이미 폐기된 행(revokedAt IS NOT NULL)은 갱신하지 않습니다.
interface RefreshTokenJpaRepository : JpaRepository<RefreshTokenJpaEntity, Long> {
    fun findByTokenHash(tokenHash: String): RefreshTokenJpaEntity?

    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revokedAt = :now WHERE r.id = :id AND r.revokedAt IS NULL")
    fun revokeById(
        @Param("id") id: Long,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revokedAt = :now WHERE r.userId = :userId AND r.revokedAt IS NULL")
    fun revokeAllByUserId(
        @Param("userId") userId: Long,
        @Param("now") now: LocalDateTime,
    ): Int
}
