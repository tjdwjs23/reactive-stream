package demo.board.adapter.`in`.web

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

// 현재 인증된 사용자의 정보(id + 관리자 여부)를 리액티브 보안 컨텍스트에서 꺼냅니다.
// 리소스 서버가 검증한 principal은 Jwt이며, 발급 시 sub에 사용자 id를, roles 클레임으로 권한을 넣었으므로
// subject를 id로, 권한 목록에 ROLE_ADMIN이 있는지로 관리자 여부를 판별합니다.
// 컨트롤러가 작성자 id·요청자 인가 정보를 얻는 seam으로, 표준 WebTestClient 단위 테스트에서는
// 이 컴포넌트를 목으로 주입해 보안 컨텍스트 없이도 검증할 수 있습니다.
@Component
class AuthenticatedUserProvider {
    suspend fun current(): AuthenticatedUser {
        val context =
            ReactiveSecurityContextHolder
                .getContext()
                .awaitSingleOrNull()
                ?: throw IllegalStateException("보안 컨텍스트가 없습니다.")
        val authentication =
            context.authentication
                ?: throw IllegalStateException("인증 정보가 없습니다.")
        val id =
            (authentication.principal as? Jwt)?.subject?.toLong()
                ?: throw IllegalStateException("예상치 못한 인증 주체 타입입니다.")
        val isAdmin = authentication.authorities.any { it.authority == ROLE_ADMIN }
        return AuthenticatedUser(id = id, isAdmin = isAdmin)
    }

    // 작성자 id만 필요한 경로(게시글 생성)를 위한 축약. current().id와 동일합니다.
    suspend fun currentUserId(): Long = current().id

    private companion object {
        const val ROLE_ADMIN = "ROLE_ADMIN"
    }
}

// 인증된 요청자의 최소 정보. id는 작성자 기록/소유권 검사에, isAdmin은 관리자 우회 인가에 씁니다.
data class AuthenticatedUser(
    val id: Long,
    val isAdmin: Boolean,
)
