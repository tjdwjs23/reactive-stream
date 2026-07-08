package demo.search.adapter.out.observability

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

// 상품 비즈니스 사건이 product.* 메트릭으로 정확히 변환되는지 인메모리 레지스트리로 검증합니다.
class MicrometerProductObservabilityAdapterTest :
    StringSpec({

        "생성/삭제는 product.* 카운터를 증가시킨다" {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerProductObservabilityAdapter(registry)

            adapter.productCreated()
            adapter.productCreated()
            adapter.productDeleted()

            registry.get("product.create").counter().count() shouldBe 2.0
            registry.get("product.delete").counter().count() shouldBe 1.0
        }

        "검색/자동완성은 수행 카운터 + 적중 분포를 기록한다" {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerProductObservabilityAdapter(registry)

            adapter.productSearched(3)
            adapter.productAutocompleted(5)
            adapter.productAutocompleted(0)

            registry.get("product.search").counter().count() shouldBe 1.0
            registry.get("product.search.hits").summary().totalAmount() shouldBe 3.0
            registry.get("product.autocomplete").counter().count() shouldBe 2.0
            registry.get("product.autocomplete.hits").summary().count() shouldBe 2L
        }

        "아웃박스 백로그는 최신값 게이지로 노출된다" {
            val registry = SimpleMeterRegistry()
            val adapter = MicrometerProductObservabilityAdapter(registry)

            registry.get("product.outbox.unpublished").gauge().value() shouldBe 0.0
            adapter.updateOutboxBacklog(7)
            registry.get("product.outbox.unpublished").gauge().value() shouldBe 7.0
            adapter.updateOutboxBacklog(0)
            registry.get("product.outbox.unpublished").gauge().value() shouldBe 0.0
        }
    })
