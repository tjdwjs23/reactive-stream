package demo.hexagonal.hexagonalback.application.port.`in`

import demo.hexagonal.hexagonalback.domain.model.Board
import kotlinx.coroutines.flow.Flow

// 1. 게시글 생성 유즈케이스
interface CreateBoardUseCase {
    suspend fun createBoard(command: CreateBoardCommand): Board
}

data class CreateBoardCommand(
    val title: String,
    val content: String,
) {
    init {
        // 입력 모델의 유효성 검증 (Self-Validating)
        require(title.isNotBlank()) { "Title must not be blank" }
        require(content.length >= 10) { "Content must be at least 10 characters" }
    }
}

// 2. 게시글 조회 유즈케이스
interface GetBoardUseCase {
    suspend fun getBoard(id: Long): Board

    fun getAllBoards(): Flow<Board>
}

// 3. 게시글 수정 유즈케이스
interface UpdateBoardUseCase {
    suspend fun updateBoard(command: UpdateBoardCommand): Board
}

// CreateBoardCommand와 달리 init 블록 검증이 없습니다.
// 수정 시 제목 공백 여부는 Board.update()가 도메인 규칙으로 검증하며,
// 내용 최소 길이는 최초 생성 시에만 강제합니다 (수정은 기존 내용을 줄이는 것을 허용).
data class UpdateBoardCommand(
    val id: Long,
    val title: String,
    val content: String,
)

// 4. 게시글 삭제 유즈케이스
interface DeleteBoardUseCase {
    suspend fun deleteBoard(id: Long)
}
