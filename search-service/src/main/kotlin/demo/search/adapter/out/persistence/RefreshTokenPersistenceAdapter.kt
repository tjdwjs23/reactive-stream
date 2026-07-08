package demo.search.adapter.out.persistence

import demo.search.application.port.out.RefreshTokenPort
import demo.search.domain.model.RefreshToken
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

// RefreshTokenPort의 JPA 구현. 서비스는 이 클래스를 모르고 포트에만 의존합니다(포트-어댑터 경계).
// 폐기(@Modifying JPQL UPDATE)는 트랜잭션이 필요하므로 해당 메서드에 @Transactional을 둡니다.
// 폐기 시각(now)은 주입된 Clock에서 만들어 넘깁니다(도메인/서비스가 벽시계를 직접 읽지 않는 원칙과 정합).
@Repository
class RefreshTokenPersistenceAdapter(
    private val repository: RefreshTokenJpaRepository,
    private val mapper: RefreshTokenMapper,
    private val clock: Clock,
) : RefreshTokenPort {
    override fun save(token: RefreshToken) {
        repository.save(mapper.toEntity(token))
    }

    override fun findByHash(tokenHash: String): RefreshToken? =
        repository.findByTokenHash(tokenHash)?.let(mapper::toDomain)

    @Transactional
    override fun revoke(id: Long) {
        repository.revokeById(id, LocalDateTime.now(clock))
    }

    @Transactional
    override fun revokeAllForUser(userId: Long) {
        repository.revokeAllByUserId(userId, LocalDateTime.now(clock))
    }
}
