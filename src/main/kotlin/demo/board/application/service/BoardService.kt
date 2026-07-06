package demo.board.application.service

import demo.board.application.port.`in`.BoardPage
import demo.board.application.port.`in`.BoardPageQuery
import demo.board.application.port.`in`.CreateBoardCommand
import demo.board.application.port.`in`.CreateBoardUseCase
import demo.board.application.port.`in`.DeleteBoardUseCase
import demo.board.application.port.`in`.GetBoardUseCase
import demo.board.application.port.`in`.UpdateBoardCommand
import demo.board.application.port.`in`.UpdateBoardUseCase
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardSearchPort
import demo.board.application.port.out.BoardViewCountPort
import demo.board.domain.exception.BoardNotFoundException
import demo.board.domain.model.Board
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.milliseconds

// 트랜잭션 경계는 "DB 접근"에만 좁게 둡니다. 클래스 레벨 @Transactional을 붙이면
// ES 색인/Redis 증가 같은 외부 자원 부수효과까지 DB 트랜잭션 안(그리고 커밋 전)에서 실행돼,
// (1) 트랜잭션이 열린 채 외부 왕복을 기다려 R2DBC 커넥션을 오래 점유하고
// (2) DB가 롤백돼도 ES에는 이미 반영되는 정합성 위험이 생깁니다.
// 그래서 쓰기는 단일 문장(R2DBC 자동 커밋)으로 처리하고 색인은 커밋 이후에 베스트에포트로 수행하며,
// 목록 조회도 단일 SELECT라 트랜잭션 없이 오토커밋으로 읽습니다(트랜잭션은 DB 이득이 있을 때만).
// (여러 문장을 원자적으로 묶어야 하는 흐름이 생기면, R2DBC 스택에서 Spring이 자동 구성하는
//  ReactiveTransactionManager + @Transactional을 그 메서드에만 좁게 붙이면 됩니다.)
@Service
class BoardService(
    private val boardRepositoryPort: BoardRepositoryPort,
    private val boardViewCountPort: BoardViewCountPort,
    // 한글 전문검색 색인. 쓰기 경로에서 베스트에포트로 동기화합니다(실패해도 게시글 저장은 성공).
    private val boardSearchPort: BoardSearchPort,
    // 생성 시각 주입용 시계. 도메인이 벽시계를 직접 읽지 않도록 여기서 now를 만들어 넘깁니다(테스트에서 고정 가능).
    private val clock: Clock,
    // 조회수 증가(Redis)에 허용하는 시간 예산. Redis가 느리거나 죽어도 조회 지연이 여기서 상한선을 가집니다.
    @Value("\${board.view-count.increment-timeout-ms:200}") private val incrementTimeoutMs: Long = 200,
) : CreateBoardUseCase,
    GetBoardUseCase,
    UpdateBoardUseCase,
    DeleteBoardUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun createBoard(command: CreateBoardCommand): Board {
        // 도메인 객체 생성 (생성 시각은 주입된 Clock에서 만들어 넘깁니다 — 도메인이 벽시계를 직접 읽지 않음)
        val newBoard =
            Board(
                title = command.title,
                content = command.content,
                createdAt = LocalDateTime.now(clock),
                authorId = command.authorId,
            )
        // 포트를 통해 저장 (단일 INSERT → R2DBC 자동 커밋, ID가 부여된 객체가 반환됨)
        val saved = boardRepositoryPort.save(newBoard)
        indexSafely(saved) // 저장(커밋) 후 검색 색인에 베스트에포트 반영 — 트랜잭션 밖 부수효과
        return saved
    }

    // 단건 조회는 조회수 1건을 반영합니다. DB에는 즉시 쓰지 않고 Redis에 델타를 누적(INCR)한 뒤,
    // 응답에는 DB 누적값 + 아직 반영 안 된 델타를 더해 실시간 조회수를 보여줍니다.
    // DB 읽기(단일 SELECT, 자동 커밋)를 먼저 끝내고 Redis 증가는 그 뒤에 수행합니다 —
    // @Transactional로 메서드 전체를 감싸면 Redis 왕복 동안 DB 커넥션을 붙잡게 되므로 붙이지 않습니다.
    override suspend fun getBoard(id: Long): Board {
        val board =
            boardRepositoryPort.findById(id) // (1) DB에서 확정값 읽기 (SELECT만!)
                ?: throw BoardNotFoundException(id)
        val pendingDelta = incrementViewCountSafely(id) // (2) Redis에 +1, 누적 델타 받기 (트랜잭션 밖)
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
    // 단일 SELECT(오토커밋)라 트랜잭션이 사줄 이점이 없어 @Transactional을 붙이지 않습니다 —
    // getBoard와 동일하게 "트랜잭션은 DB 이득이 있을 때만" 원칙을 지킵니다.
    // hasNext 판정을 위해 size+1건을 조회해, 초과분이 있으면 다음 페이지가 있다고 봅니다.
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

        // 3. 변경된 객체 저장 (단일 UPDATE → 자동 커밋)
        val saved = boardRepositoryPort.save(updatedBoard)
        indexSafely(saved) // 커밋 후 수정 내용을 검색 색인에 반영(같은 id 문서 덮어쓰기) — 트랜잭션 밖 부수효과
        return saved
    }

    override suspend fun deleteBoard(id: Long) {
        boardRepositoryPort.deleteById(id) // 단일 DELETE → 자동 커밋
        deleteFromIndexSafely(id) // 커밋 후 검색 색인에서도 제거(베스트에포트) — 트랜잭션 밖 부수효과
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
