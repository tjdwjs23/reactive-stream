package demo.board.adapter.out.persistence

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

// refresh_tokens R2DBC 리포지토리. 저장/조회는 파생 쿼리, 폐기(UPDATE)는 @Modifying @Query로 명시합니다.
// 폐기 시각은 DB의 now()를 써(앱 시계 주입 불필요) 이미 폐기된 행(revoked_at IS NOT NULL)은 건드리지 않습니다.
interface RefreshTokenR2dbcRepository : CoroutineCrudRepository<RefreshTokenR2dbcEntity, Long> {
    suspend fun findByTokenHash(tokenHash: String): RefreshTokenR2dbcEntity?

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
    suspend fun revokeById(id: Long): Long

    @Modifying
    @Query("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
    suspend fun revokeAllByUserId(userId: Long): Long
}
