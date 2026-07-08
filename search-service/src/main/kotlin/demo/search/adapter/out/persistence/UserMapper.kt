package demo.search.adapter.out.persistence

import demo.search.domain.model.Role
import demo.search.domain.model.User
import org.springframework.stereotype.Component

// User(도메인) ↔ UserJpaEntity(영속) 변환의 유일한 지점. BoardMapper와 동일한 관례입니다.
// role은 도메인 enum ↔ DB 문자열로 변환합니다.
@Component
class UserMapper {
    fun toEntity(domain: User): UserJpaEntity =
        UserJpaEntity(
            id = domain.id,
            username = domain.username,
            passwordHash = domain.passwordHash,
            role = domain.role.name,
            createdAt = domain.createdAt,
        )

    fun toDomain(entity: UserJpaEntity): User =
        User(
            id = entity.id,
            username = entity.username,
            passwordHash = entity.passwordHash,
            role = Role.valueOf(entity.role),
            createdAt = entity.createdAt,
        )
}
