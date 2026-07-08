package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.ArchiveResult
import demo.board.application.port.`in`.ArchiveStaleBoardsCommand
import demo.board.application.port.`in`.ArchiveStaleBoardsUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 오래된 게시글을 지금 즉시 일괄 아카이브(삭제)하는 운영/시험용 구동 어댑터.
// 평소에는 StaleBoardArchivingScheduler(@Scheduled)가 같은 유즈케이스를 구동하지만,
// 부하 테스트에서 배치 삭제 처리량을 결정적으로 측정하거나 운영에서 즉시 정리가 필요할 때 이 엔드포인트로 트리거합니다.
// 스케줄러와 동일하게 유즈케이스 인터페이스에만 의존합니다(구현체·R2DBC를 모릅니다).
// 접근 통제는 SecurityConfig가 /api/admin/** 를 ROLE_ADMIN으로 제한합니다.
@Tag(name = "Admin", description = "운영/시험용 API (조회수 플러시·게시글 아카이브)")
@RestController
@RequestMapping("/api/admin/boards")
class AdminBoardArchiveController(
    private val archiveStaleBoardsUseCase: ArchiveStaleBoardsUseCase,
) {
    @Operation(
        summary = "오래된 게시글 즉시 아카이브",
        description =
            "retentionDays보다 오래된 게시글을 지금 즉시 청크 단위로 삭제하고 결과 요약(scanned/deleted/failedChunks)을 " +
                "반환합니다. 삭제는 되돌릴 수 없으므로 retentionDays는 필수이며(실수로 대량 삭제 방지), chunkSize/concurrency로 " +
                "처리량을 조절합니다. ROLE_ADMIN 권한(Bearer 토큰)이 필요합니다.",
    )
    // 아카이브 유즈케이스는 내부적으로 코루틴(Channel 팬아웃)을 쓰는 유일한 경로라 suspend입니다.
    // MVC 핸들러(블로킹)에서 그 suspend를 runBlocking으로 브리지합니다(코루틴 경계를 어댑터 안에 가둠 —
    // 스케줄러 StaleBoardArchivingScheduler와 동일한 방식).
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/archive")
    fun archive(
        @RequestBody request: ArchiveBoardsRequest,
    ): ResponseEntity<SuccessResponse<ArchiveResult>> {
        // chunkSize/concurrency의 기본값은 커맨드(ArchiveStaleBoardsCommand)를 단일 출처로 삼고,
        // 요청에 값이 있을 때만 덮어씁니다(엔드포인트가 기본값을 따로 중복 정의하지 않도록).
        // 커맨드 init이 retentionDays/chunkSize/concurrency 범위를 자가검증합니다
        // (위반 시 IllegalArgumentException → GlobalExceptionHandler가 400으로 매핑).
        val defaults = ArchiveStaleBoardsCommand(retentionDays = request.retentionDays)
        val command =
            defaults.copy(
                chunkSize = request.chunkSize ?: defaults.chunkSize,
                concurrency = request.concurrency ?: defaults.concurrency,
            )
        return SuccessResponse.ok(runBlocking { archiveStaleBoardsUseCase.archiveStaleBoards(command) })
    }
}

// 온디맨드 아카이브 요청. retentionDays는 안전상 기본값 없이 필수입니다(누락 시 400).
// chunkSize/concurrency는 생략(null) 시 컨트롤러가 커맨드 기본값을 채웁니다.
data class ArchiveBoardsRequest(
    val retentionDays: Long,
    val chunkSize: Int? = null,
    val concurrency: Int? = null,
)
