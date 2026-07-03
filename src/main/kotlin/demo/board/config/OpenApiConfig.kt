package demo.board.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
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
        OpenAPI().info(
            Info()
                .title("Reactive Stream Board API")
                .description(
                    "WebFlux + 코루틴 + R2DBC 기반 게시판 API (헥사고날 아키텍처). " +
                        "응답은 BaseResponse(code/status/result)로 통일됩니다.",
                ).version("v1"),
        )
}
