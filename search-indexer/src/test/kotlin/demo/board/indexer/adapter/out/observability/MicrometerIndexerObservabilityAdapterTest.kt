package demo.board.indexer.adapter.out.observability

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

// 어댑터가 IndexerObservabilityPort 호출을 올바른 이름·타입의 Micrometer 메터로 변환하는지 실제 레지스트리로 검증합니다.
// (SimpleMeterRegistry는 OTLP 없이 메터 값을 인메모리로 보관 → 이름/증가량을 그대로 단언 가능.)
class MicrometerIndexerObservabilityAdapterTest :
    BehaviorSpec({

        Given("SimpleMeterRegistry에 연결된 어댑터") {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerIndexerObservabilityAdapter(registry)

            When("색인·삭제·DLQ 격리를 기록하면") {
                adapter.boardsIndexed(3)
                adapter.boardsIndexed(2)
                adapter.boardsDeleted(1)
                adapter.messageDeadLettered()
                adapter.messageDeadLettered()

                Then("각 카운터가 present-tense 이름으로 누적된다") {
                    registry.get("board.indexer.indexed").counter().count() shouldBe 5.0
                    registry.get("board.indexer.deleted").counter().count() shouldBe 1.0
                    registry.get("board.indexer.dlq").counter().count() shouldBe 2.0
                }
            }

            When("recordIndexingBatch로 블록을 감싸면") {
                var ran = false
                adapter.recordIndexingBatch { ran = true }

                Then("블록을 실행하고 타이머에 소요 시간을 기록한다") {
                    ran shouldBe true
                    registry.get("board.indexer.batch").timer().count() shouldBe 1L
                }
            }

            When("감싼 블록이 예외를 던지면") {
                Then("예외는 전파되지만 시도 지연은 타이머에 남는다") {
                    val before = registry.get("board.indexer.batch").timer().count()
                    runCatching { adapter.recordIndexingBatch { error("ES 실패") } }
                    registry.get("board.indexer.batch").timer().count() shouldBe (before + 1)
                }
            }
        }
    })
