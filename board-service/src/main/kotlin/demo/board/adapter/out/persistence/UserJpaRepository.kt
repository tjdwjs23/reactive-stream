package demo.board.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository

// 파생 쿼리(findByUsername/existsByUsername)는 Spring Data JPA가 메서드 이름으로 JPQL을 생성합니다.
// username에는 UNIQUE 제약이 있어 단건 조회가 보장됩니다.
interface UserJpaRepository : JpaRepository<UserJpaEntity, Long> {
    fun findByUsername(username: String): UserJpaEntity?

    fun existsByUsername(username: String): Boolean
}
