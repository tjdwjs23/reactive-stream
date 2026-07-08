package demo.search.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// springdoc-openapi(Spring MVC) 문서의 최상위 메타데이터를 정의합니다.
// - Swagger UI:   /swagger-ui.html
// - OpenAPI JSON: /v3/api-docs
// 엔드포인트별 상세(요약/응답 코드)는 각 컨트롤러의 @Operation/@Tag가 채웁니다.
@Configuration
class OpenApiConfig {
    @Bean
    fun boardOpenAPI(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Board API")
                    .description(
                        "Spring MVC + 가상 스레드 + JPA 기반 게시판 API (헥사고날 아키텍처). " +
                            "응답은 BaseResponse(code/status/result)로 통일됩니다.",
                    ).version("v1"),
            )
            // 쓰기/admin 엔드포인트는 자체 발급 JWT(Authorization: Bearer)로 보호됩니다.
            // bearer 보안 스킴을 선언해 두면 Swagger UI에 Authorize(토큰 입력)가 노출되고,
            // 각 엔드포인트의 @SecurityRequirement("bearer-jwt")가 이 스킴을 참조합니다.
            .components(
                Components().addSecuritySchemes(
                    BEARER_JWT_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("로그인(/api/auth/login)으로 받은 액세스 토큰"),
                ),
            )

    companion object {
        // @SecurityRequirement가 참조하는 스킴 이름.
        const val BEARER_JWT_SCHEME = "bearer-jwt"
    }
}
