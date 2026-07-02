package demo.reactivestream.application.service

import demo.reactivestream.application.port.`in`.BoardPage
import demo.reactivestream.application.port.`in`.BoardPageQuery
import demo.reactivestream.application.port.`in`.CreateBoardCommand
import demo.reactivestream.application.port.`in`.CreateBoardUseCase
import demo.reactivestream.application.port.`in`.DeleteBoardUseCase
import demo.reactivestream.application.port.`in`.GetBoardUseCase
import demo.reactivestream.application.port.`in`.UpdateBoardCommand
import demo.reactivestream.application.port.`in`.UpdateBoardUseCase
import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.domain.exception.BoardNotFoundException
import demo.reactivestream.domain.model.Board
import kotlinx.coroutines.flow.toList
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

    // 키셋 페이지네이션. 한 페이지(size)만 읽으므로 여기서 collect(toList)해도 요청당 메모리가 일정합니다.
    // suspend 함수 내부에서 즉시 소비하므로 @Transactional(readOnly)가 실제 읽기를 정확히 감쌉니다.
    // hasNext 판정을 위해 size+1건을 조회해, 초과분이 있으면 다음 페이지가 있다고 봅니다.
    @Transactional(readOnly = true)
    override suspend fun getBoards(query: BoardPageQuery): BoardPage {
        val rows = boardRepositoryPort.findPage(query.cursor, query.size + 1).toList()
        val hasNext = rows.size > query.size
        val items = if (hasNext) rows.take(query.size) else rows
        return BoardPage(
            items = items,
            nextCursor = if (hasNext) items.last().id else null,
            hasNext = hasNext,
        )
    }

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
