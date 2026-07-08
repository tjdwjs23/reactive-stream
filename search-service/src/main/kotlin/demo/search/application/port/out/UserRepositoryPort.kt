package demo.search.application.port.out

import demo.search.domain.model.User

// 사용자 영속화 out-port. 구체 기술(JPA)은 어댑터가 감춥니다(BoardRepositoryPort와 동일한 관례).
interface UserRepositoryPort {
    fun save(user: User): User

    fun findByUsername(username: String): User?

    // Refresh Token 재발급 시 토큰에 담긴 user_id로 사용자를 복원하는 데 씁니다.
    fun findById(id: Long): User?

    fun existsByUsername(username: String): Boolean
}
