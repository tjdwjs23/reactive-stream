package demo.board.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// springdoc-openapi(WebFlux) 문서의 최상위 메타데이터를 정의합니다.
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
                    .title("Reactive Stream Board API")
                    .description(
                        "WebFlux + 코루틴 + R2DBC 기반 게시판 API (헥사고날 아키텍처). " +
                            "응답은 BaseResponse(code/status/result)로 통일됩니다.",
                    ).version("v1"),
            )
            // admin 엔드포인트(재색인/조회수 플러시)는 X-Admin-Token 헤더로 보호됩니다.
            // 여기서 apiKey 보안 스킴을 선언해 두면 Swagger UI에 Authorize 입력이 노출되고,
            // 각 엔드포인트의 @SecurityRequirement("admin-token")가 이 스킴을 참조합니다.
            .components(
                Components().addSecuritySchemes(
                    ADMIN_TOKEN_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-Admin-Token")
                        .description("운영/시험용 admin 엔드포인트 보호 토큰(board.admin.token과 일치해야 함)"),
                ),
            )

    companion object {
        // @SecurityRequirement가 참조하는 스킴 이름.
        const val ADMIN_TOKEN_SCHEME = "admin-token"
    }
}
