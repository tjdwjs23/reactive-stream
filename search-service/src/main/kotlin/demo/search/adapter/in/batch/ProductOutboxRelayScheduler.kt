package demo.search.adapter.`in`.batch

import demo.search.application.port.`in`.ProductRelayOutboxUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 상품 아웃박스 릴레이의 주기 폴링 트리거. board의 OutboxRelayScheduler와 동일하게 board.outbox.relay.enabled=true일 때만
// 빈으로 등록됩니다(같은 스위치로 두 릴레이를 함께 켜고 끕니다 — 테스트/로컬은 Kafka 없이 조용).
@Component
@ConditionalOnProperty(prefix = "search.outbox.relay", name = ["enabled"], havingValue = "true")
class ProductOutboxRelayScheduler(
    private val productRelayOutboxUseCase: ProductRelayOutboxUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${search.outbox.relay.poll-interval-ms:1000}")
    fun run() {
        val result = productRelayOutboxUseCase.relay()
        if (result.published > 0) {
            log.info("product outbox relay published {} event(s)", result.published)
        }
    }
}
