package demo.search.application.port.out

import demo.search.application.port.`in`.AuthToken
import demo.search.domain.model.User

// 액세스 토큰 발급 out-port. JWT 서명/클레임 구성 같은 구체 기술은 어댑터(NimbusJwtTokenAdapter)가 감춥니다.
// 애플리케이션은 "사용자에게 발급할 토큰을 받는다"는 계약만 압니다.
interface AuthTokenPort {
    fun issue(user: User): AuthToken
}
