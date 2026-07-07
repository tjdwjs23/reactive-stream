package demo.board.application.port.out

import demo.board.domain.model.User

// 사용자 영속화 out-port. 구체 기술(R2DBC)은 어댑터가 감춥니다(BoardRepositoryPort와 동일한 관례).
interface UserRepositoryPort {
    suspend fun save(user: User): User

    suspend fun findByUsername(username: String): User?

    suspend fun existsByUsername(username: String): Boolean
}
