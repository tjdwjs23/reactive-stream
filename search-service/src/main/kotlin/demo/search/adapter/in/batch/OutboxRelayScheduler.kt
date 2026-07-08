package demo.search.adapter.`in`.batch

import demo.search.application.port.`in`.RelayOutboxUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// Driving Adapter: 주기 폴링이 아웃박스 릴레이 유즈케이스를 "구동"하는 입력 어댑터입니다.
// 웹 컨트롤러/아카이브 스케줄러와 마찬가지로 유즈케이스 인터페이스에만 의존합니다.
//
// board.outbox.relay.enabled=true일 때만 빈으로 등록됩니다(아카이브 배치처럼 운영에서 opt-in) —
// 비활성 시엔 빈 자체가 없어 스케줄도 걸리지 않으므로, 테스트/로컬에서 Kafka 없이도 조용합니다.
@Component
@ConditionalOnProperty(prefix = "board.outbox.relay", name = ["enabled"], havingValue = "true")
class OutboxRelayScheduler(
    private val relayOutboxUseCase: RelayOutboxUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // fixedDelay: 이전 사이클이 끝난 뒤 간격을 둡니다(중첩 실행 방지). 기본 1초.
    @Scheduled(fixedDelayString = "\${board.outbox.relay.poll-interval-ms:1000}")
    fun run() {
        // 릴레이는 순차 발행이라 블로킹으로 직접 호출합니다(코루틴 불필요). 스케줄러 스레드는 가상 스레드가 아니지만
        // 폴링 1건이라 부담이 없고, 발행 자체는 짧습니다.
        val result = relayOutboxUseCase.relay()
        if (result.published > 0) {
            log.info("outbox relay published {} event(s)", result.published)
        }
    }
}
