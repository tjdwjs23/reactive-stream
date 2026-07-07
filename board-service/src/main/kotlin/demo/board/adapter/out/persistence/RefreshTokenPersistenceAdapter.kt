package demo.board.adapter.out.persistence

import demo.board.application.port.out.RefreshTokenPort
import demo.board.domain.model.RefreshToken
import org.springframework.stereotype.Repository

// RefreshTokenPort의 R2DBC 구현. 서비스는 이 클래스를 모르고 포트에만 의존합니다(포트-어댑터 경계).
@Repository
class RefreshTokenPersistenceAdapter(
    private val repository: RefreshTokenR2dbcRepository,
    private val mapper: RefreshTokenMapper,
) : RefreshTokenPort {
    override suspend fun save(token: RefreshToken) {
        repository.save(mapper.toEntity(token))
    }

    override suspend fun findByHash(tokenHash: String): RefreshToken? =
        repository.findByTokenHash(tokenHash)?.let { mapper.toDomain(it) }

    override suspend fun revoke(id: Long) {
        repository.revokeById(id)
    }

    override suspend fun revokeAllForUser(userId: Long) {
        repository.revokeAllByUserId(userId)
    }
}
