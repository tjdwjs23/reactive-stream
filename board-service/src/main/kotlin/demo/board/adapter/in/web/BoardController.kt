package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.BoardPageQuery
import demo.board.application.port.`in`.CreateBoardCommand
import demo.board.application.port.`in`.CreateBoardUseCase
import demo.board.application.port.`in`.DeleteBoardCommand
import demo.board.application.port.`in`.DeleteBoardUseCase
import demo.board.application.port.`in`.GetBoardUseCase
import demo.board.application.port.`in`.UpdateBoardCommand
import demo.board.application.port.`in`.UpdateBoardUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

// BoardService 전체가 아닌 UseCase 인터페이스를 개별 주입합니다.
// 컨트롤러가 구현체에 의존하지 않고, 각 핸들러가 어떤 유즈케이스를 사용하는지 명시적으로 드러납니다.
@Tag(name = "Board", description = "게시판 CRUD 및 키셋 페이지네이션 목록 API")
@RestController
@RequestMapping("/api/boards")
class BoardController(
    private val createBoardUseCase: CreateBoardUseCase,
    private val getBoardUseCase: GetBoardUseCase,
    private val updateBoardUseCase: UpdateBoardUseCase,
    private val deleteBoardUseCase: DeleteBoardUseCase,
    private val boardWebMapper: BoardWebMapper,
    // 인증된 사용자 id를 보안 컨텍스트에서 꺼내 작성자로 넘깁니다(생성 경로는 인증 필수).
    private val authenticatedUserProvider: AuthenticatedUserProvider,
) {
    @Operation(
        summary = "게시글 생성",
        description = "제목과 내용(10자 이상)으로 게시글을 만들고 201 Created + Location을 반환합니다. 인증(Bearer 토큰)이 필요합니다.",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @PostMapping
    fun createBoard(
        @RequestBody request: CreateBoardRequest,
    ): ResponseEntity<SuccessResponse<BoardResponse>> {
        val command =
            CreateBoardCommand(
                title = request.title,
                content = request.content,
                authorId = authenticatedUserProvider.currentUserId(),
            )
        val response = boardWebMapper.toResponse(createBoardUseCase.createBoard(command))
        return SuccessResponse.created(response, URI.create("/api/boards/${response.id}"))
    }

    @Operation(summary = "게시글 단건 조회", description = "id로 게시글을 조회합니다. 없으면 404를 반환합니다.")
    @GetMapping("/{id}")
    fun getBoard(
        @PathVariable id: Long,
    ): ResponseEntity<SuccessResponse<BoardResponse>> =
        SuccessResponse.ok(boardWebMapper.toResponse(getBoardUseCase.getBoard(id)))

    // 키셋 페이지네이션: ?cursor=<마지막 id>&size=<페이지 크기>. cursor 생략 시 최신부터.
    @Operation(
        summary = "게시글 목록(키셋 페이지네이션)",
        description = "id 내림차순으로 size건을 반환합니다. 다음 페이지는 응답의 nextCursor를 cursor로 다시 넘겨 조회합니다.",
    )
    @GetMapping
    fun getBoards(
        @Parameter(description = "마지막으로 본 게시글 id. 생략 시 최신부터 조회") @RequestParam(required = false) cursor: Long?,
        @Parameter(description = "페이지 크기(1~100)") @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<SuccessResponse<BoardPageResponse>> {
        val page = getBoardUseCase.getBoards(BoardPageQuery(cursor = cursor, size = size))
        return SuccessResponse.ok(boardWebMapper.toPageResponse(page))
    }

    @Operation(
        summary = "게시글 수정",
        description =
            "제목/내용을 수정합니다. 제목은 공백일 수 없으며, 없는 게시글이면 404입니다. " +
                "인증(Bearer 토큰)이 필요하며, 소유자 또는 관리자만 수정할 수 있습니다(그 외 403).",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @PutMapping("/{id}")
    fun updateBoard(
        @PathVariable id: Long,
        @RequestBody request: UpdateBoardRequest,
    ): ResponseEntity<SuccessResponse<BoardResponse>> {
        val requester = authenticatedUserProvider.current()
        val command =
            UpdateBoardCommand(
                id = id,
                title = request.title,
                content = request.content,
                requesterId = requester.id,
                requesterIsAdmin = requester.isAdmin,
            )
        return SuccessResponse.ok(boardWebMapper.toResponse(updateBoardUseCase.updateBoard(command)))
    }

    @Operation(
        summary = "게시글 삭제",
        description =
            "id로 게시글을 삭제하고 본문 없는 204 No Content를 반환합니다. " +
                "인증(Bearer 토큰)이 필요하며, 소유자 또는 관리자만 삭제할 수 있습니다(그 외 403).",
    )
    @SecurityRequirement(name = "bearer-jwt")
    @DeleteMapping("/{id}")
    fun deleteBoard(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        val requester = authenticatedUserProvider.current()
        deleteBoardUseCase.deleteBoard(
            DeleteBoardCommand(id = id, requesterId = requester.id, requesterIsAdmin = requester.isAdmin),
        )
        return SuccessResponse.noContent()
    }
}
