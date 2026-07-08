package demo.search.application.port.out

import demo.search.domain.model.RefreshToken

// 리프레시 토큰 영속화 out-port. 저장 매체(테이블)는 어댑터가 감춥니다. 서비스는 이 포트로만 다룹니다.
interface RefreshTokenPort {
    fun save(token: RefreshToken)

    // 제시된 토큰의 해시로 조회. 없으면 null(무효 토큰).
    fun findByHash(tokenHash: String): RefreshToken?

    // 단건 폐기(회전 시 기존 토큰). 이미 폐기된 건은 no-op.
    fun revoke(id: Long)

    // 사용자의 모든 활성 토큰 폐기. 재사용 감지(세션 탈취 의심) 시 해당 사용자의 세션을 전부 무효화합니다.
    fun revokeAllForUser(userId: Long)
}
