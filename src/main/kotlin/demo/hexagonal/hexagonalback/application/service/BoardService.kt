package demo.hexagonal.hexagonalback.application.service

import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardCommand
import demo.hexagonal.hexagonalback.application.port.`in`.CreateBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.DeleteBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.GetBoardUseCase
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardCommand
import demo.hexagonal.hexagonalback.application.port.`in`.UpdateBoardUseCase
import demo.hexagonal.hexagonalback.application.port.out.BoardRepositoryPort
import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.model.Board
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
// 클래스 레벨 @Transactional이 모든 메서드에 기본 적용됩니다.
// R2DBC 스택에서는 Spring이 ReactiveTransactionManager를 자동 구성하며, @Transactional은
// suspend 함수에도 그대로 적용됩니다(코루틴 컨텍스트를 통해 리액티브 트랜잭션이 전파됨).
// 조회 메서드는 @Transactional(readOnly = true)로 재정의합니다.
@Transactional
class BoardService(
    private val boardRepositoryPort: BoardRepositoryPort,
) : CreateBoardUseCase,
    GetBoardUseCase,
    UpdateBoardUseCase,
    DeleteBoardUseCase {
    override suspend fun createBoard(command: CreateBoardCommand): Board {
        // 도메인 객체 생성
        val newBoard =
            Board(
                title = command.title,
                content = command.content,
            )
        // 포트를 통해 저장 (ID가 부여된 객체가 반환됨)
        return boardRepositoryPort.save(newBoard)
    }

    @Transactional(readOnly = true)
    override suspend fun getBoard(id: Long): Board =
        boardRepositoryPort.findById(id)
            ?: throw BoardNotFoundException(id)

    // Flow는 지연 스트림이라 트랜잭션 경계 안에서 즉시 소비되지 않습니다.
    // 실제 구독(collect)은 어댑터(컨트롤러)에서 일어나므로 여기서는 그대로 흘려보냅니다.
    @Transactional(readOnly = true)
    override fun getAllBoards(): Flow<Board> = boardRepositoryPort.findAll()

    override suspend fun updateBoard(command: UpdateBoardCommand): Board {
        // 1. 기존 게시글 조회
        val existingBoard =
            boardRepositoryPort.findById(command.id)
                ?: throw BoardNotFoundException(command.id)

        // 2. 도메인 로직 실행 (내용 수정)
        // Board가 data class(immutable)라면 copy로 새 객체를 만듭니다.
        val updatedBoard = existingBoard.update(command.title, command.content)

        // 3. 변경된 객체 저장
        return boardRepositoryPort.save(updatedBoard)
    }

    override suspend fun deleteBoard(id: Long) {
        boardRepositoryPort.deleteById(id)
    }
}
