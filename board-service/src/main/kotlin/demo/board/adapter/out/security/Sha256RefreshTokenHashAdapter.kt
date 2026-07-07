package demo.board.adapter.out.security

import demo.board.application.port.out.RefreshTokenHashPort
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// RefreshTokenHashPort의 순수 JDK 구현. 외부 의존성 없이 SecureRandom(원문 생성) + SHA-256(해시)만 씁니다.
//  - generateToken: 256비트 난수를 URL-safe Base64로. 추측 불가능한 불투명 토큰(클라이언트 반환용).
//  - hash: 원문의 SHA-256을 소문자 hex로. 결정적이라 조회 키로 쓰기에 적합하고, DB엔 이 해시만 저장됩니다.
@Component
class Sha256RefreshTokenHashAdapter : RefreshTokenHashPort {
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    override fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
