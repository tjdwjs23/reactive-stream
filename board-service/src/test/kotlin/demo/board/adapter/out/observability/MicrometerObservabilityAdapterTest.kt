package demo.board.adapter.out.observability

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

// 비즈니스 사건이 Micrometer 메트릭으로 정확히 변환되는지 인메모리 레지스트리(SimpleMeterRegistry)로 검증합니다.
class MicrometerObservabilityAdapterTest :
    StringSpec({

        "카운터 사건은 board.* 카운터를 1씩 증가시킨다" {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerObservabilityAdapter(registry)

            adapter.boardCreated()
            adapter.boardUpdated()
            adapter.boardUpdated()
            adapter.boardDeleted()
            adapter.boardViewed()

            registry.get("board.create").counter().count() shouldBe 1.0
            registry.get("board.update").counter().count() shouldBe 2.0
            registry.get("board.delete").counter().count() shouldBe 1.0
            registry.get("board.view").counter().count() shouldBe 1.0
        }

        "검색 사건은 수행 횟수 카운터와 적중 건수 분포를 함께 기록한다" {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerObservabilityAdapter(registry)

            adapter.boardSearched(3)
            adapter.boardSearched(0)

            registry.get("board.search").counter().count() shouldBe 2.0
            val hits = registry.get("board.search.hits").summary()
            hits.count() shouldBe 2L
            hits.totalAmount() shouldBe 3.0
        }

        "플러시/아카이브 사건은 건수만큼 카운터를 증가시킨다" {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerObservabilityAdapter(registry)

            adapter.viewCountsFlushed(10)
            adapter.boardsArchived(5)

            registry.get("board.view-count.flush").counter().count() shouldBe 10.0
            registry.get("board.archive").counter().count() shouldBe 5.0
        }
    })
