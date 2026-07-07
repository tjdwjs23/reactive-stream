package demo.board.application.service

import demo.board.application.port.`in`.BoardPage
import demo.board.application.port.`in`.BoardPageQuery
import demo.board.application.port.`in`.CreateBoardCommand
import demo.board.application.port.`in`.CreateBoardUseCase
import demo.board.application.port.`in`.DeleteBoardCommand
import demo.board.application.port.`in`.DeleteBoardUseCase
import demo.board.application.port.`in`.GetBoardUseCase
import demo.board.application.port.`in`.UpdateBoardCommand
import demo.board.application.port.`in`.UpdateBoardUseCase
import demo.board.application.port.out.BoardEventOutboxPort
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardViewCountPort
import demo.board.application.port.out.ObservabilityPort
import demo.board.application.port.out.TransactionRunnerPort
import demo.board.domain.exception.BoardAccessDeniedException
import demo.board.domain.exception.BoardNotFoundException
import demo.board.domain.model.Board
import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

// 트랜잭션 경계는 "DB 접근"에만 좁게 둡니다. 클래스 레벨 @Transactional을 붙이면 Redis 증가 같은 외부 자원
// 부수효과까지 DB 트랜잭션 안(그리고 커밋 전)에서 실행돼, R2DBC 커넥션을 오래 점유하고 정합성 위험이 생깁니다.
// 그래서 조회수 증가(Redis)는 트랜잭션 밖에서 수행하고, 목록/단건 조회도 단일 SELECT라 트랜잭션 없이 읽습니다.
//
// 단 하나의 예외가 "쓰기 + 아웃박스 기록"입니다. Transactional Outbox는 게시글 반영과 이벤트 기록이 반드시
// 원자적이어야 유실(DB엔 반영됐는데 이벤트는 유실)이 없으므로, 이 두 문장만 TransactionRunnerPort로 묶습니다.
// 검색 색인은 더 이상 쓰기 경로에서 인라인으로 하지 않습니다 — 아웃박스 이벤트를 Kafka로 발행하고
// search-indexer가 소비해 ES에 반영합니다(색인 책임이 쓰기 경로 밖으로 완전히 분리됨).
@Service
class BoardService(
    private val boardRepositoryPort: BoardRepositoryPort,
    private val boardViewCountPort: BoardViewCountPort,
    // 게시글 변경 이벤트 아웃박스. 쓰기와 같은 트랜잭션에서 기록됩니다(Transactional Outbox).
    private val boardEventOutboxPort: BoardEventOutboxPort,
    // "쓰기 + 아웃박스 기록"을 원자적으로 묶는 트랜잭션 경계. Spring 트랜잭션 기술은 어댑터가 감춥니다.
    private val transactionRunner: TransactionRunnerPort,
    // 도메인 비즈니스 메트릭(생성/조회/수정/삭제 카운트). 구체 기술(Micrometer)은 어댑터가 감춥니다.
    private val observability: ObservabilityPort,
    // 생성 시각/이벤트 발생 시각 주입용 시계. 도메인이 벽시계를 직접 읽지 않도록 여기서 만들어 넘깁니다(테스트에서 고정 가능).
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
        // 저장(INSERT) + 아웃박스 기록을 하나의 트랜잭션으로 묶는다 — 둘 다 반영되거나 둘 다 롤백된다.
        val saved =
            transactionRunner.execute {
                val persisted = boardRepositoryPort.save(newBoard)
                boardEventOutboxPort.record(persisted.toChangedEvent(BoardChangeType.CREATED))
                persisted
            }
        observability.boardCreated()
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
        observability.boardViewed()
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

        // 2. 소유권 인가: 소유자 또는 관리자만 수정 가능(IDOR 방지). 도메인 로직 실행 전에 검사한다.
        assertCanModify(existingBoard, command.requesterId, command.requesterIsAdmin)

        // 3. 도메인 로직 실행 (내용 수정). Board가 data class(immutable)라면 copy로 새 객체를 만듭니다.
        val updatedBoard = existingBoard.update(command.title, command.content)

        // 4. 저장(UPDATE) + 아웃박스 기록을 하나의 트랜잭션으로 묶는다.
        val saved =
            transactionRunner.execute {
                val persisted = boardRepositoryPort.save(updatedBoard)
                boardEventOutboxPort.record(persisted.toChangedEvent(BoardChangeType.UPDATED))
                persisted
            }
        observability.boardUpdated()
        return saved
    }

    override suspend fun deleteBoard(command: DeleteBoardCommand) {
        // 삭제 전 대상을 먼저 읽어 소유권을 검사한다(없으면 404). 인가를 위해 blind delete 대신 조회를 선행한다.
        val board =
            boardRepositoryPort.findById(command.id)
                ?: throw BoardNotFoundException(command.id)
        assertCanModify(board, command.requesterId, command.requesterIsAdmin)

        // 삭제(DELETE) + 아웃박스 기록(DELETED)을 하나의 트랜잭션으로 묶는다.
        transactionRunner.execute {
            boardRepositoryPort.deleteById(command.id)
            boardEventOutboxPort.record(deletedEvent(command.id))
        }
        observability.boardDeleted()
    }

    // 소유자 또는 관리자만 수정/삭제할 수 있습니다. 그 외에는 403(BoardAccessDeniedException).
    // 소유권 규칙 자체는 도메인(Board.isOwnedBy)에 두고, 여기서는 "관리자면 통과"라는 인가 정책만 조합합니다.
    private fun assertCanModify(
        board: Board,
        requesterId: Long,
        requesterIsAdmin: Boolean,
    ) {
        if (requesterIsAdmin) return
        if (!board.isOwnedBy(requesterId)) {
            throw BoardAccessDeniedException(board.id, requesterId)
        }
    }

    // 저장된 게시글을 색인 소비자가 그대로 반영할 수 있는 변경 이벤트로 변환합니다.
    // eventId는 소비자 멱등 키(중복 발행 무시), 시각은 주입된 Clock 기준입니다(LocalDateTime→Instant는 Clock의 zone으로).
    private fun Board.toChangedEvent(type: BoardChangeType): BoardChangedEvent =
        BoardChangedEvent(
            eventId = UUID.randomUUID().toString(),
            boardId = requireNotNull(id) { "저장된 게시글은 id가 있어야 합니다" },
            type = type,
            title = title,
            content = content,
            authorId = authorId,
            createdAt = createdAt.atZone(clock.zone).toInstant(),
            occurredAt = Instant.now(clock),
        )

    // 삭제 이벤트: 색인에서 제거만 하면 되므로 본문 필드는 비웁니다(boardId만 유효).
    private fun deletedEvent(boardId: Long): BoardChangedEvent =
        BoardChangedEvent(
            eventId = UUID.randomUUID().toString(),
            boardId = boardId,
            type = BoardChangeType.DELETED,
            occurredAt = Instant.now(clock),
        )
}
