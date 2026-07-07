package demo.board.application.service

import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardViewCountPort
import demo.board.application.port.out.DistributedLockPort
import demo.board.support.NoOpObservabilityPort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Duration

// 락을 항상 획득해 블록을 그대로 실행하는 테스트 더블(단일 인스턴스·경합 없음 상황을 재현).
private object AlwaysAcquiringLock : DistributedLockPort {
    override suspend fun <T> withLock(
        key: String,
        ttl: Duration,
        block: suspend () -> T,
    ): T? = block()
}

// 락을 절대 획득하지 못하는 더블(다른 인스턴스가 이미 플러시 중인 상황을 재현). 블록을 실행하지 않고 null을 반환.
private object NeverAcquiringLock : DistributedLockPort {
    override suspend fun <T> withLock(
        key: String,
        ttl: Duration,
        block: suspend () -> T,
    ): T? = null
}

private class FlushFixture(
    chunkSize: Int = 1000,
    lock: DistributedLockPort = AlwaysAcquiringLock,
) {
    val boardViewCountPort = mockk<BoardViewCountPort>()
    val boardRepositoryPort = mockk<BoardRepositoryPort>()
    val service =
        FlushBoardViewCountsService(
            boardViewCountPort,
            boardRepositoryPort,
            NoOpObservabilityPort,
            lock,
            chunkSize,
            lockTtlMs = 300_000,
        )

    init {
        // commit-then-delete: 반영 성공 청크는 버퍼에서 지웁니다. 기본은 무동작으로 둡니다.
        coEvery { boardViewCountPort.removeDrained(any()) } returns Unit
    }
}

class FlushBoardViewCountsServiceTest :
    BehaviorSpec({

        Given("Redis에 여러 게시글의 조회 델타가 쌓여 있을 때") {
            val fixture = FlushFixture()
            coEvery { fixture.boardViewCountPort.snapshotPendingDeltas() } returns mapOf(1L to 3L, 2L to 5L)
            coEvery { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(1L to 3L, 2L to 5L)) } returns 2

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("한 번의 배치 UPDATE로 델타를 반영하고, 반영 성공분을 버퍼에서 지운다") {
                    result.boards shouldBe 2
                    result.updatedRows shouldBe 2
                    result.failed shouldBe 0
                    coVerify(exactly = 1) { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(1L to 3L, 2L to 5L)) }
                    // commit-then-delete: 반영 성공 후에만 스냅샷에서 제거
                    coVerify(exactly = 1) { fixture.boardViewCountPort.removeDrained(setOf(1L, 2L)) }
                }
            }
        }

        Given("청크 크기보다 델타가 많을 때") {
            val fixture = FlushFixture(chunkSize = 2)
            coEvery { fixture.boardViewCountPort.snapshotPendingDeltas() } returns mapOf(1L to 1L, 2L to 1L, 3L to 1L)
            coEvery { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(1L to 1L, 2L to 1L)) } returns 2
            coEvery { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(3L to 1L)) } returns 1

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("청크 단위로 나눠 여러 번의 배치 UPDATE로 반영하고, 청크별로 버퍼에서 지운다") {
                    result.boards shouldBe 3
                    result.updatedRows shouldBe 3
                    result.failed shouldBe 0
                    coVerify(exactly = 1) { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(1L to 1L, 2L to 1L)) }
                    coVerify(exactly = 1) { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(3L to 1L)) }
                    coVerify(exactly = 1) { fixture.boardViewCountPort.removeDrained(setOf(1L, 2L)) }
                    coVerify(exactly = 1) { fixture.boardViewCountPort.removeDrained(setOf(3L)) }
                }
            }
        }

        Given("쌓인 델타가 없을 때") {
            val fixture = FlushFixture()
            coEvery { fixture.boardViewCountPort.snapshotPendingDeltas() } returns emptyMap()

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("DB를 건드리지 않고 빈 결과를 반환한다") {
                    result.boards shouldBe 0
                    result.updatedRows shouldBe 0
                    result.failed shouldBe 0
                    coVerify(exactly = 0) { fixture.boardRepositoryPort.addViewCountsBatch(any()) }
                    coVerify(exactly = 0) { fixture.boardViewCountPort.removeDrained(any()) }
                }
            }
        }

        Given("다른 인스턴스가 이미 플러시 중이라 분산 락을 잡지 못할 때") {
            val fixture = FlushFixture(lock = NeverAcquiringLock)

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("스냅샷도 DB도 건드리지 않고 빈 결과를 반환한다(이번 차례는 스킵)") {
                    result.boards shouldBe 0
                    result.updatedRows shouldBe 0
                    result.failed shouldBe 0
                    coVerify(exactly = 0) { fixture.boardViewCountPort.snapshotPendingDeltas() }
                    coVerify(exactly = 0) { fixture.boardRepositoryPort.addViewCountsBatch(any()) }
                }
            }
        }

        Given("일부 청크의 DB 반영이 실패할 때") {
            val fixture = FlushFixture(chunkSize = 2)
            coEvery { fixture.boardViewCountPort.snapshotPendingDeltas() } returns mapOf(1L to 1L, 2L to 1L, 3L to 1L)
            coEvery { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(1L to 1L, 2L to 1L)) } returns 2
            coEvery { fixture.boardRepositoryPort.addViewCountsBatch(mapOf(3L to 1L)) } throws
                RuntimeException("db down")

            When("flush를 호출하면") {
                val result = fixture.service.flush()

                Then("실패 청크는 지우지 않아(재시도 대상) 나머지만 반영·정리하고 failed로 집계한다") {
                    result.boards shouldBe 3
                    result.updatedRows shouldBe 2
                    result.failed shouldBe 1
                    // 성공 청크만 버퍼에서 제거되고, 실패 청크(3L)는 남아 다음 플러시가 재시도한다
                    coVerify(exactly = 1) { fixture.boardViewCountPort.removeDrained(setOf(1L, 2L)) }
                    coVerify(exactly = 0) { fixture.boardViewCountPort.removeDrained(setOf(3L)) }
                }
            }
        }
    })
