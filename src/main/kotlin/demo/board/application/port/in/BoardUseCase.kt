package demo.board.application.port.`in`

import demo.board.domain.model.Board
import demo.board.domain.model.Board.Companion.MAX_TITLE_LENGTH
import demo.board.domain.model.Board.Companion.MIN_CONTENT_LENGTH

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
        // DB 스키마의 title VARCHAR(255)와 정합. 초과 시 raw DB 에러(500) 대신 여기서 400으로 거른다.
        // 길이 한도는 도메인(Board)이 단일 소스로 보유한다.
        require(title.length <= MAX_TITLE_LENGTH) { "Title must be at most $MAX_TITLE_LENGTH characters" }
        // 내용 최소 길이도 도메인(Board)이 단일 소스로 보유하며, 수정(Board.update)과 같은 값을 공유한다.
        require(content.length >= MIN_CONTENT_LENGTH) { "Content must be at least $MIN_CONTENT_LENGTH characters" }
    }
}

// 2. 게시글 조회 유즈케이스
interface GetBoardUseCase {
    suspend fun getBoard(id: Long): Board

    // 목록은 키셋(seek) 페이지네이션으로 조회합니다. 한 번에 size건만 읽으므로
    // OFFSET 방식/전체 조회와 달리 데이터가 커져도 요청당 메모리/지연이 일정합니다.
    suspend fun getBoards(query: BoardPageQuery): BoardPage
}

// 커서 페이지 요청. cursor가 null이면 첫 페이지(최신)부터, 아니면 그 id보다 과거를 읽습니다.
data class BoardPageQuery(
    val cursor: Long? = null,
    val size: Int = 20,
) {
    init {
        require(size in 1..100) { "size must be between 1 and 100" }
        require(cursor == null || cursor > 0) { "cursor must be positive" }
    }
}

// 커서 페이지 결과. nextCursor는 다음 요청에 그대로 넘길 커서이며, 다음 페이지가 없으면 null입니다.
data class BoardPage(
    val items: List<Board>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

// 3. 게시글 수정 유즈케이스
interface UpdateBoardUseCase {
    suspend fun updateBoard(command: UpdateBoardCommand): Board
}

// CreateBoardCommand와 달리 여기엔 init 블록 검증이 없습니다.
// 수정 규칙은 도메인이 단일 소스로 강제합니다 — Board.update()가 제목 공백·제목 길이(MAX_TITLE_LENGTH)와
// 내용 최소 길이(MIN_CONTENT_LENGTH)를 생성(CreateBoardCommand)과 동일하게 대칭 검증합니다.
data class UpdateBoardCommand(
    val id: Long,
    val title: String,
    val content: String,
)

// 4. 게시글 삭제 유즈케이스
interface DeleteBoardUseCase {
    suspend fun deleteBoard(id: Long)
}
