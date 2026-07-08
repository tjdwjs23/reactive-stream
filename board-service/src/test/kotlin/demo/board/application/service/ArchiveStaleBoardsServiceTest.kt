package demo.board.application.service

import demo.board.application.port.`in`.ArchiveStaleBoardsCommand
import demo.board.application.port.out.BoardBatchQueryPort
import demo.board.application.port.out.BoardEventOutboxPort
import demo.board.application.port.out.TransactionRunnerPort
import demo.board.domain.model.Board
import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import demo.board.support.NoOpObservabilityPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections

// 아웃박스에 기록된 이벤트를 수집하는 페이크. 삭제와 같은 트랜잭션에서 DELETED 이벤트가 남는지 검증합니다.
private class RecordingOutboxPort : BoardEventOutboxPort {
    val events: MutableList<BoardChangedEvent> = Collections.synchronizedList(mutableListOf())

    override suspend fun record(event: BoardChangedEvent) {
        events.add(event)
    }

    override suspend fun recordAll(events: List<BoardChangedEvent>) {
        this.events.addAll(events)
    }
}

// 트랜잭션 경계를 그대로 통과시키는 러너(단위 테스트용). 실제 원자성은 통합 테스트에서 검증합니다.
private val passThroughRunner =
    object : TransactionRunnerPort {
        override suspend fun <T> execute(block: suspend () -> T): T = block()
    }

// 실제 DB 대신 인메모리 페이크 out-port. 스트리밍/청크/내결함성 흐름을 결정적으로 검증합니다.
private class FakeBoardBatchQueryPort(
    private val data: List<Board>,
    private val failOnId: Long? = null,
    private val failAll: Boolean = false,
) : BoardBatchQueryPort {
    // 여러 워커가 동시에 기록하므로 thread-safe 리스트 사용
    val deletedIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    // 페이크는 일부러 "필터 없이 전부" 돌려줍니다.
    // → 삭제 대상 확정은 서비스가 Board.isStale로 다시 한다는 점(도메인 규칙 권위)을 검증하기 위함.
    override fun findStaleBoards(
        before: LocalDateTime,
        pageSize: Int,
    ): Flow<Board> = data.asFlow()

    override suspend fun deleteByIds(ids: List<Long>): Int {
        if (failAll || (failOnId != null && ids.contains(failOnId))) {
            throw IllegalStateException("forced failure on chunk (ids=$ids)")
        }
        deletedIds.addAll(ids)
        return ids.size
    }
}

class ArchiveStaleBoardsServiceTest :
    BehaviorSpec({

        val now = LocalDateTime.now()

        // 배치가 주입 시계로 "지금"을 읽으므로, 서비스의 now가 이 테스트의 now와 정확히 일치하도록 고정 시계를 넘깁니다.
        val zone: ZoneId = ZoneId.systemDefault()
        val clock: Clock = Clock.fixed(now.atZone(zone).toInstant(), zone)

        Given("오래된 게시글과 최신 게시글이 섞여 있을 때") {
            val stale = (1L..5L).map { Board(id = it, title = "t$it", content = "c", createdAt = now.minusDays(400)) }
            val fresh = (6L..8L).map { Board(id = it, title = "t$it", content = "c", createdAt = now.minusDays(10)) }
            val fakePort = FakeBoardBatchQueryPort(stale + fresh)
            val outbox = RecordingOutboxPort()
            val service = ArchiveStaleBoardsService(fakePort, outbox, passThroughRunner, NoOpObservabilityPort, clock)

            When("보관 기간 365일로 배치를 실행하면") {
                val result =
                    service.archiveStaleBoards(
                        ArchiveStaleBoardsCommand(retentionDays = 365, chunkSize = 2, concurrency = 3),
                    )

                Then("오래된 게시글만 삭제된다") {
                    fakePort.deletedIds shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 4L, 5L)
                }

                Then("도메인 규칙(isStale)이 최종 권위라 최신 게시글은 삭제되지 않는다") {
                    (6L..8L).forEach { fakePort.deletedIds shouldNotContain it }
                }

                Then("삭제된 각 게시글마다 DELETED 아웃박스 이벤트가 (삭제와 같은 트랜잭션에서) 기록된다") {
                    outbox.events.map { it.boardId } shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 4L, 5L)
                    outbox.events.all { it.type == BoardChangeType.DELETED } shouldBe true
                }

                Then("결과 요약이 정확하다") {
                    result.scanned shouldBe 8
                    result.deleted shouldBe 5
                    result.failedChunks shouldBe 0
                }
            }
        }

        Given("특정 청크 삭제가 실패하도록 설정됐을 때") {
            val stale = (1L..6L).map { Board(id = it, title = "t$it", content = "c", createdAt = now.minusDays(400)) }
            val fakePort = FakeBoardBatchQueryPort(stale, failOnId = 3L)
            val outbox = RecordingOutboxPort()
            val service = ArchiveStaleBoardsService(fakePort, outbox, passThroughRunner, NoOpObservabilityPort, clock)

            When("배치를 실행하면") {
                val result =
                    service.archiveStaleBoards(
                        ArchiveStaleBoardsCommand(retentionDays = 365, chunkSize = 2, concurrency = 1),
                    )

                Then("실패한 청크는 건너뛰고 나머지는 계속 삭제한다(내결함성)") {
                    result.scanned shouldBe 6
                    result.deleted shouldBe 4
                    result.failedChunks shouldBe 1
                    // id 3이 포함된 청크(3,4)만 실패, 나머지(1,2,5,6)는 삭제됨
                    fakePort.deletedIds shouldContainExactlyInAnyOrder listOf(1L, 2L, 5L, 6L)
                }

                Then("실패한 청크는 이벤트도 기록되지 않고(같은 트랜잭션), 성공한 청크만 DELETED 이벤트가 남는다") {
                    outbox.events.map { it.boardId } shouldContainExactlyInAnyOrder listOf(1L, 2L, 5L, 6L)
                    outbox.events.all { it.type == BoardChangeType.DELETED } shouldBe true
                }
            }
        }

        Given("시도한 모든 청크의 삭제가 실패할 때") {
            val stale = (1L..4L).map { Board(id = it, title = "t$it", content = "c", createdAt = now.minusDays(400)) }
            val fakePort = FakeBoardBatchQueryPort(stale, failAll = true)
            val service =
                ArchiveStaleBoardsService(
                    fakePort,
                    RecordingOutboxPort(),
                    passThroughRunner,
                    NoOpObservabilityPort,
                    clock,
                )

            When("배치를 실행하면") {
                Then("부분 실패와 달리 예외로 전체 실패를 신호한다(스케줄러가 성공으로 오인하지 않도록)") {
                    shouldThrow<IllegalStateException> {
                        service.archiveStaleBoards(
                            ArchiveStaleBoardsCommand(retentionDays = 365, chunkSize = 2, concurrency = 1),
                        )
                    }
                    fakePort.deletedIds.size shouldBe 0
                }
            }
        }
    })
