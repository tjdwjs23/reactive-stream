package demo.board.adapter.out.persistence

import demo.board.domain.model.RefreshToken
import org.springframework.stereotype.Component

// RefreshToken(도메인) ↔ RefreshTokenR2dbcEntity(영속) 변환의 유일한 지점(BoardMapper/UserMapper와 동일 관례).
@Component
class RefreshTokenMapper {
    fun toEntity(domain: RefreshToken): RefreshTokenR2dbcEntity =
        RefreshTokenR2dbcEntity(
            id = domain.id,
            userId = domain.userId,
            tokenHash = domain.tokenHash,
            expiresAt = domain.expiresAt,
            revokedAt = domain.revokedAt,
            createdAt = domain.createdAt,
        )

    fun toDomain(entity: RefreshTokenR2dbcEntity): RefreshToken =
        RefreshToken(
            id = entity.id,
            userId = entity.userId,
            tokenHash = entity.tokenHash,
            expiresAt = entity.expiresAt,
            revokedAt = entity.revokedAt,
            createdAt = entity.createdAt,
        )
}
