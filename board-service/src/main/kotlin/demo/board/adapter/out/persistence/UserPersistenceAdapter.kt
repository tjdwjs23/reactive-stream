package demo.board.adapter.out.persistence

import demo.board.application.port.out.UserRepositoryPort
import demo.board.domain.model.User
import org.springframework.stereotype.Repository

// UserRepositoryPort의 JPA 구현. 서비스는 이 클래스를 모르고 포트에만 의존합니다(포트-어댑터 경계).
@Repository
class UserPersistenceAdapter(
    private val userJpaRepository: UserJpaRepository,
    private val userMapper: UserMapper,
) : UserRepositoryPort {
    override fun save(user: User): User = userMapper.toDomain(userJpaRepository.save(userMapper.toEntity(user)))

    override fun findByUsername(username: String): User? =
        userJpaRepository.findByUsername(username)?.let(userMapper::toDomain)

    override fun findById(id: Long): User? = userJpaRepository.findById(id).orElse(null)?.let(userMapper::toDomain)

    override fun existsByUsername(username: String): Boolean = userJpaRepository.existsByUsername(username)
}
