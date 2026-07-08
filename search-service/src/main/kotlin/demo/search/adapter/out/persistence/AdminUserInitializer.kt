package demo.search.adapter.out.persistence

import demo.search.application.port.out.PasswordEncoderPort
import demo.search.application.port.out.UserRepositoryPort
import demo.search.domain.model.Role
import demo.search.domain.model.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

// 기동 시 관리자(ROLE_ADMIN) 계정을 부트스트랩합니다. 가입 API는 항상 ROLE_USER만 만들므로,
// 관리자는 여기서만(설정 기반) 생성됩니다 — admin 엔드포인트(reindex/flush)를 호출할 계정의 유일한 출처입니다.
// - search.security.admin.password가 비어 있으면 생성을 건너뛰고 경고합니다(로컬/개발 편의).
// - 이미 같은 username이 있으면 멱등하게 건너뜁니다.
// BoardSearchIndexInitializer와 동일하게 @EventListener(ApplicationReadyEvent)로 기동 직후 한 번 실행합니다(순수 블로킹).
@Component
class AdminUserInitializer(
    private val userRepositoryPort: UserRepositoryPort,
    private val passwordEncoderPort: PasswordEncoderPort,
    private val clock: Clock,
    @Value("\${search.security.admin.username:admin}") private val adminUsername: String,
    @Value("\${search.security.admin.password:}") private val adminPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createAdminIfAbsent() {
        if (adminPassword.isBlank()) {
            log.warn(
                "search.security.admin.password is not set — skipping admin bootstrap. " +
                    "Set SEARCH_ADMIN_PASSWORD to enable admin endpoints (reindex/flush).",
            )
            return
        }
        try {
            if (userRepositoryPort.existsByUsername(adminUsername)) {
                log.info("admin user '{}' already exists — skip bootstrap", adminUsername)
                return
            }
            userRepositoryPort.save(
                User(
                    username = adminUsername,
                    passwordHash = passwordEncoderPort.encode(adminPassword),
                    role = Role.ADMIN,
                    createdAt = LocalDateTime.now(clock),
                ),
            )
            log.info("admin user '{}' bootstrapped (ROLE_ADMIN)", adminUsername)
        } catch (e: Exception) {
            log.warn("failed to bootstrap admin user '{}'. cause={}", adminUsername, e.toString())
        }
    }
}
