package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.FlushBoardViewCountsUseCase
import demo.board.application.port.`in`.FlushViewCountsResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 조회수 델타를 즉시 DB로 write-back하는 운영/시험용 구동 어댑터.
// 스케줄러와 동일한 유즈케이스 인터페이스에만 의존합니다(구현체·Redis를 모릅니다).
// 접근 통제는 SecurityConfig가 /api/admin/** 를 ROLE_ADMIN으로 제한합니다.
//
// 용도:
//  - 부하 테스트에서 플러시를 온디맨드로 트리거해 지연/정합성을 결정적으로 측정.
//  - 운영에서 배포/셧다운 직전 버퍼를 강제로 비워 미반영 델타 유실 창을 줄임.
@Tag(name = "Admin", description = "조회수 write-back 운영/시험용 API")
@RestController
@RequestMapping("/api/admin/view-counts")
class AdminViewCountController(
    private val flushUseCase: FlushBoardViewCountsUseCase,
) {
    @Operation(
        summary = "조회수 즉시 플러시",
        description =
            "Redis에 쌓인 조회수 델타를 지금 즉시 DB로 write-back하고 결과 요약을 반환합니다. " +
                "ROLE_ADMIN 권한(Bearer 토큰)이 필요합니다.",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/flush")
    fun flush(): ResponseEntity<SuccessResponse<FlushViewCountsResult>> = SuccessResponse.ok(flushUseCase.flush())
}
