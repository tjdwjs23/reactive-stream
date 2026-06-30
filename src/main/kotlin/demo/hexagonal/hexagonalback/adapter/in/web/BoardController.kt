package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardCommand
import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.DeleteBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.GetBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardCommand
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

// BoardService 전체가 아닌 UseCase 인터페이스를 개별 주입합니다.
// 컨트롤러가 구현체에 의존하지 않고, 각 핸들러가 어떤 유즈케이스를 사용하는지 명시적으로 드러납니다.
@RestController
@RequestMapping("/api/boards")
class BoardController(
    private val createBoardUseCase: CreateBoardUseCase,
    private val getBoardUseCase: GetBoardUseCase,
    private val updateBoardUseCase: UpdateBoardUseCase,
    private val deleteBoardUseCase: DeleteBoardUseCase,
    private val boardWebMapper: BoardWebMapper,
) {
    @PostMapping
    fun createBoard(
        @RequestBody request: CreateBoardRequest,
    ): ResponseEntity<BoardResponse> {
        val command = CreateBoardCommand(title = request.title, content = request.content)
        val response = boardWebMapper.toResponse(createBoardUseCase.createBoard(command))
        return ResponseEntity.created(URI.create("/api/boards/${response.id}")).body(response)
    }

    @GetMapping("/{id}")
    fun getBoard(
        @PathVariable id: Long,
    ): ResponseEntity<BoardResponse> = ResponseEntity.ok(boardWebMapper.toResponse(getBoardUseCase.getBoard(id)))

    @GetMapping
    fun getAllBoards(): ResponseEntity<List<BoardResponse>> =
        ResponseEntity.ok(boardWebMapper.toResponseList(getBoardUseCase.getAllBoards()))

    @PutMapping("/{id}")
    fun updateBoard(
        @PathVariable id: Long,
        @RequestBody request: UpdateBoardRequest,
    ): ResponseEntity<BoardResponse> {
        val command = UpdateBoardCommand(id = id, title = request.title, content = request.content)
        return ResponseEntity.ok(boardWebMapper.toResponse(updateBoardUseCase.updateBoard(command)))
    }

    @DeleteMapping("/{id}")
    fun deleteBoard(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        deleteBoardUseCase.deleteBoard(id)
        return ResponseEntity.noContent().build()
    }
}
