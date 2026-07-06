package demo.board.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

// 파생 쿼리(findByUsername/existsByUsername)는 Spring Data R2DBC가 메서드 이름으로 SQL을 생성합니다.
// username에는 UNIQUE 제약이 있어 단건 조회가 보장됩니다.
interface UserR2dbcRepository : CoroutineCrudRepository<UserR2dbcEntity, Long> {
    suspend fun findByUsername(username: String): UserR2dbcEntity?

    suspend fun existsByUsername(username: String): Boolean
}
