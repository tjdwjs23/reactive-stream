package demo.board.adapter.`in`.web

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

// 현재 인증된 사용자의 id를 리액티브 보안 컨텍스트에서 꺼냅니다.
// 리소스 서버가 검증한 principal은 Jwt이며, 발급 시 sub에 사용자 id를 넣었으므로 그것을 읽습니다.
// 컨트롤러(BoardController.createBoard)가 작성자 id를 얻는 seam으로, 표준 WebTestClient 단위 테스트에서는
// 이 컴포넌트를 목으로 주입해 보안 컨텍스트 없이도 검증할 수 있습니다.
@Component
class AuthenticatedUserProvider {
    suspend fun currentUserId(): Long {
        val context =
            ReactiveSecurityContextHolder
                .getContext()
                .awaitSingleOrNull()
                ?: throw IllegalStateException("no security context")
        val principal = context.authentication?.principal
        return (principal as? Jwt)?.subject?.toLong()
            ?: throw IllegalStateException("unexpected authentication principal type")
    }
}
