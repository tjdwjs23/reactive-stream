package demo.board.adapter.out.persistence

import demo.board.application.port.out.UserRepositoryPort
import demo.board.domain.model.User
import org.springframework.stereotype.Repository

// UserRepositoryPort의 R2DBC 구현. 서비스는 이 클래스를 모르고 포트에만 의존합니다(포트-어댑터 경계).
@Repository
class UserPersistenceAdapter(
    private val userR2dbcRepository: UserR2dbcRepository,
    private val userMapper: UserMapper,
) : UserRepositoryPort {
    override suspend fun save(user: User): User =
        userMapper.toDomain(userR2dbcRepository.save(userMapper.toEntity(user)))

    override suspend fun findByUsername(username: String): User? =
        userR2dbcRepository.findByUsername(username)?.let { userMapper.toDomain(it) }

    override suspend fun existsByUsername(username: String): Boolean = userR2dbcRepository.existsByUsername(username)
}
