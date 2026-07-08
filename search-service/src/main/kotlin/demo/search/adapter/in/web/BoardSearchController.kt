package demo.search.adapter.`in`.web

import demo.search.application.port.`in`.BoardSearchQuery
import demo.search.application.port.`in`.ReindexBoardsUseCase
import demo.search.application.port.`in`.SearchBoardUseCase
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
    fun search(
        @Parameter(description = "검색어(공백 불가)") @RequestParam keyword: String,
        @Parameter(description = "가져올 결과 수(1~100)") @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<SuccessResponse<BoardSearchResponse>> {
        val hits = searchBoardUseCase.search(BoardSearchQuery(keyword = keyword, size = size))
        return SuccessResponse.ok(boardWebMapper.toSearchResponse(keyword, hits))
    }

    @Operation(
        summary = "전체 재색인(무중단, alias 스왑)",
        description =
            "DB(정본)를 새 버전 인덱스에 재구축한 뒤 'boards' alias를 원자적으로 스왑합니다(무중단). " +
                "매핑을 바꿔 인덱스를 다시 만들거나 이벤트 유실로 인한 색인 누락을 회복할 때 사용합니다. " +
                "색인 실패가 있으면(failed>0) alias를 옮기지 않아(swapped=false) 검색은 기존 인덱스를 그대로 봅니다(자동 롤백). " +
                "ROLE_ADMIN 권한(Bearer 토큰)이 필요합니다.",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping("/reindex")
    fun reindex(): ResponseEntity<SuccessResponse<ReindexResponse>> {
        val result = reindexBoardsUseCase.reindexAll()
        return SuccessResponse.ok(
            ReindexResponse(reindexed = result.indexed, failed = result.failed, swapped = result.swapped),
        )
    }
}

data class ReindexResponse(
    val reindexed: Long,
    val failed: Long,
    // alias('boards')를 새 버전 인덱스로 스왑했는지 여부. failed>0이면 false(롤백).
    val swapped: Boolean,
)
