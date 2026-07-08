package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.BoardSearchQuery
import demo.board.application.port.`in`.ReindexBoardsUseCase
import demo.board.application.port.`in`.SearchBoardUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// 한글 전문검색 API. BoardController(CRUD)와 분리해 검색 유즈케이스만 주입받습니다.
// 경로 /api/boards/search 는 리터럴 세그먼트라 /api/boards/{id}(Long)보다 우선 매칭됩니다.
@Tag(name = "Board Search", description = "Elasticsearch(Nori) 기반 한글 전문검색 및 재색인 API")
@RestController
@RequestMapping("/api/boards/search")
class BoardSearchController(
    private val searchBoardUseCase: SearchBoardUseCase,
    private val reindexBoardsUseCase: ReindexBoardsUseCase,
    private val boardWebMapper: BoardWebMapper,
) {
    @Operation(
        summary = "게시글 전문검색",
        description =
            "keyword를 Nori로 형태소 분석해 title/content를 검색하고, 관련도(_score) 내림차순으로 반환합니다. " +
                "매칭 부분은 <em> 태그로 하이라이트됩니다.",
    )
    @GetMapping
    suspend fun search(
        @Parameter(description = "검색어(공백 불가)") @RequestParam keyword: String,
        @Parameter(description = "가져올 결과 수(1~100)") @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<SuccessResponse<BoardSearchResponse>> {
        val hits = searchBoardUseCase.search(BoardSearchQuery(keyword = keyword, size = size))
        return SuccessResponse.ok(boardWebMapper.toSearchResponse(keyword, hits))
    }

    @Operation(
        summary = "전체 재색인",
        description =
            "DB(정본)를 순회하며 ES 색인을 다시 채웁니다. 인덱스를 새로 만들었거나 이벤트 유실로 인한 색인 누락을 회복할 때 사용합니다. " +
                "ROLE_ADMIN 권한(Bearer 토큰)이 필요합니다.",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/reindex")
    suspend fun reindex(): ResponseEntity<SuccessResponse<ReindexResponse>> {
        val result = reindexBoardsUseCase.reindexAll()
        return SuccessResponse.ok(
            ReindexResponse(reindexed = result.indexed, failed = result.failed, pruned = result.pruned),
        )
    }
}

data class ReindexResponse(
    val reindexed: Long,
    val failed: Long,
    // 정본(DB)에 없어 정리한 고아 색인 문서 수.
    val pruned: Long,
)
