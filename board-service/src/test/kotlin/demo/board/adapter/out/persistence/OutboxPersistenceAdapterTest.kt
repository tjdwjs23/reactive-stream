package demo.board.adapter.out.persistence

import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import demo.board.support.TestContainers
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// 아웃박스 어댑터(기록/미발행 조회/발행표시/백로그 카운트)를 실제 Postgres(Testcontainers)로 검증합니다.
// 컨테이너를 다른 스펙과 공유하므로 절대 개수 대신 "이 스펙이 넣은 만큼의 변화"를 상대적으로 검증합니다.
@SpringBootTest
class OutboxPersistenceAdapterTest(
    @Autowired private val adapter: OutboxPersistenceAdapter,
) : BehaviorSpec({

        fun createdEvent(boardId: Long) =
            BoardChangedEvent(
                eventId = UUID.randomUUID().toString(),
                boardId = boardId,
                type = BoardChangeType.CREATED,
                title = "제목-$boardId",
                content = "내용-$boardId",
                authorId = 1L,
                viewCount = 0L,
                createdAt = LocalDateTime.now(),
                occurredAt = Instant.now(),
            )

        Given("미발행 이벤트를 여러 건 기록하면") {
            val before = adapter.countUnpublished()
            adapter.record(createdEvent(90001L))
            adapter.record(createdEvent(90002L))
            adapter.record(createdEvent(90003L))

            When("countUnpublished / readUnpublished를 조회하면") {
                val after = adapter.countUnpublished()
                val unpublished = adapter.readUnpublished(1000)

                Then("백로그가 기록한 만큼 늘고, 방금 넣은 파티션 키가 미발행 목록에 보인다") {
                    after shouldBe before + 3
                    val keys = unpublished.map { it.partitionKey }.toSet()
                    keys.contains("90001") shouldBe true
                    keys.contains("90002") shouldBe true
                    keys.contains("90003") shouldBe true
                }
            }

            When("읽은 레코드를 markPublished로 발행 완료 표시하면") {
                val mine = adapter.readUnpublished(1000).filter { it.partitionKey.startsWith("9000") }
                val backlogBeforeMark = adapter.countUnpublished()
                adapter.markPublished(mine.map { it.id })

                Then("백로그가 발행 완료한 건수만큼 줄어든다") {
                    adapter.countUnpublished() shouldBe backlogBeforeMark - mine.size
                }
            }
        }

        fun deletedEvent(boardId: Long) =
            BoardChangedEvent(
                eventId = UUID.randomUUID().toString(),
                boardId = boardId,
                type = BoardChangeType.DELETED,
                occurredAt = Instant.now(),
            )

        Given("recordAll로 여러 이벤트를 한 번에(벌크) 기록하면") {
            val before = adapter.countUnpublished()
            val events = listOf(deletedEvent(91001L), deletedEvent(91002L), deletedEvent(91003L))
            adapter.recordAll(events)

            When("countUnpublished / readUnpublished를 조회하면") {
                val after = adapter.countUnpublished()
                val keys = adapter.readUnpublished(1000).map { it.partitionKey }.toSet()

                Then("단건 record와 동일하게 전건이 미발행으로 적재된다(벌크 INSERT)") {
                    after shouldBe before + 3
                    keys.contains("91001") shouldBe true
                    keys.contains("91002") shouldBe true
                    keys.contains("91003") shouldBe true
                }
            }
        }

        Given("빈 목록으로 recordAll을 호출하면") {
            When("recordAll(emptyList())을 호출하면") {
                val before = adapter.countUnpublished()
                adapter.recordAll(emptyList())

                Then("아무 것도 바뀌지 않는다(no-op)") {
                    adapter.countUnpublished() shouldBe before
                }
            }
        }

        Given("빈 id 목록으로 markPublished를 호출하면") {
            When("markPublished(emptyList())을 호출하면") {
                val before = adapter.countUnpublished()
                adapter.markPublished(emptyList())

                Then("아무 것도 바뀌지 않는다(no-op)") {
                    adapter.countUnpublished() shouldBe before
                }
            }
        }
    }) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = TestContainers.registerAll(registry)
    }
}
