package demo.board.adapter.`in`.web

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

// 운영/시험용 admin 엔드포인트(조회수 즉시 플러시, 전체 재색인)를 공유 시크릿 토큰으로 보호합니다.
// 풀 스프링 시큐리티를 도입하지 않고도 "인증 없는 쓰기 트리거"를 막는 최소 장치입니다.
//
// 동작:
//  - board.admin.token 이 비어 있으면(기본값) 통과시키되 기동 시 경고를 남깁니다(로컬/개발 편의).
//  - 토큰이 설정돼 있으면 요청의 X-Admin-Token 헤더가 정확히 일치해야 하며, 아니면 401을 던집니다.
@Component
class AdminAccessGuard(
    @Value("\${board.admin.token:}") private val configuredToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (configuredToken.isBlank()) {
            log.warn(
                "board.admin.token is not set — admin endpoints (flush/reindex) are UNPROTECTED. " +
                    "Set BOARD_ADMIN_TOKEN in non-local environments.",
            )
        }
    }

    // 토큰이 설정된 환경에서는 헤더 값이 일치해야 통과합니다. 미설정 환경에서는 통과(기동 시 경고).
    // 비교는 MessageDigest.isEqual로 상수시간 처리해 토큰 값 추론(타이밍 공격) 여지를 줄입니다.
    fun verify(providedToken: String?) {
        if (configuredToken.isBlank()) return
        if (providedToken == null || !constantTimeEquals(providedToken, configuredToken)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or missing admin token")
        }
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )
}
