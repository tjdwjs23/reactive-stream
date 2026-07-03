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
import demo.reactivestream.application.port.out.BoardSearchPort
import demo.reactivestream.application.port.out.BoardViewCountPort
import demo.reactivestream.domain.exception.BoardNotFoundException
import demo.reactivestream.domain.model.Board
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.time.Duration.Companion.milliseconds

@Service
// 클래스 레벨 @Transactional이 모든 메서드에 기본 적용됩니다.
// R2DBC 스택에서는 Spring이 ReactiveTransactionManager를 자동 구성하며, @Transactional은
// suspend 함수에도 그대로 적용됩니다(코루틴 컨텍스트를 통해 리액티브 트랜잭션이 전파됨).
// 조회 메서드는 @Transactional(readOnly = true)로 재정의합니다.
@Transactional
class BoardService(
    private val boardRepositoryPort: BoardRepositoryPort,
    private val boardViewCountPort: BoardViewCountPort,
    // 한글 전문검색 색인. 쓰기 경로에서 베스트에포트로 동기화합니다(실패해도 게시글 저장은 성공).
    private val boardSearchPort: BoardSearchPort,
    // 조회수 증가(Redis)에 허용하는 시간 예산. Redis가 느리거나 죽어도 조회 지연이 여기서 상한선을 가집니다.
    @Value("\${board.view-count.increment-timeout-ms:200}") private val incrementTimeoutMs: Long = 200,
) : CreateBoardUseCase,
    GetBoardUseCase,
    UpdateBoardUseCase,
    DeleteBoardUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun createBoard(command: CreateBoardCommand): Board {
        // 도메인 객체 생성
        val newBoard =
            Board(
                title = command.title,
                content = command.content,
            )
        // 포트를 통해 저장 (ID가 부여된 객체가 반환됨)
        val saved = boardRepositoryPort.save(newBoard)
        indexSafely(saved) // 검색 색인에 베스트에포트 반영
        return saved
    }

    // 단건 조회는 조회수 1건을 반영합니다. DB에는 즉시 쓰지 않고 Redis에 델타를 누적(INCR)한 뒤,
    // 응답에는 DB 누적값 + 아직 반영 안 된 델타를 더해 실시간 조회수를 보여줍니다.
    // (readOnly 트랜잭션은 DB 읽기만 감싸며, Redis 증가는 트랜잭션 밖의 별도 자원 연산입니다.)
    @Transactional(readOnly = true)
    override suspend fun getBoard(id: Long): Board {
        val board =
            boardRepositoryPort.findById(id) // (1) DB에서 확정값 읽기 (SELECT만!)
                ?: throw BoardNotFoundException(id)
        val pendingDelta = incrementViewCountSafely(id) // (2) Redis에 +1, 누적 델타 받기
        return board.copy(viewCount = board.viewCount + pendingDelta) // (3) 합쳐서 응답
    }

    // 조회수 증가를 시간 예산 안에서 시도하고, 실패하면 0을 돌려줍니다(조회 자체는 성공).
    // TimeoutCancellationException은 강등 대상이지만, 그 외 CancellationException(상위 취소)은
    // 구조적 동시성을 위해 삼키지 않고 다시 던집니다.
    private suspend fun incrementViewCountSafely(id: Long): Long =
        try {
            withTimeout(incrementTimeoutMs.milliseconds) { boardViewCountPort.increment(id) }
        } catch (e: TimeoutCancellationException) {
            log.warn(
                "view count increment timed out (boardId={}, budget={}ms); serving DB value",
                id,
                incrementTimeoutMs,
            )
            0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("view count increment failed (boardId={}); serving DB value. cause={}", id, e.toString())
            0L
        }

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
        val saved = boardRepositoryPort.save(updatedBoard)
        indexSafely(saved) // 수정 내용을 검색 색인에 반영(같은 id 문서 덮어쓰기)
        return saved
    }

    override suspend fun deleteBoard(id: Long) {
        boardRepositoryPort.deleteById(id)
        deleteFromIndexSafely(id) // 검색 색인에서도 제거(베스트에포트)
    }

    // 색인 반영을 베스트에포트로 수행합니다. ES가 느리거나 죽어도 게시글 쓰기(DB)는 이미 성공했으므로
    // 여기서 실패는 로그만 남기고 삼킵니다. 누락분은 재색인(reindexAll)으로 회복할 수 있습니다.
    // 단, 상위 취소(CancellationException)는 구조적 동시성을 위해 다시 던집니다.
    private suspend fun indexSafely(board: Board) {
        try {
            boardSearchPort.index(board)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed to index board (id={}); search may be stale. cause={}", board.id, e.toString())
        }
    }

    private suspend fun deleteFromIndexSafely(id: Long) {
        try {
            boardSearchPort.deleteById(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed to delete board from index (id={}); search may be stale. cause={}", id, e.toString())
        }
    }
}
