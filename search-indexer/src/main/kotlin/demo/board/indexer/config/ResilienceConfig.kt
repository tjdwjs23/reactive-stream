package demo.board.indexer.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

// Resilience4j 서킷브레이커 레지스트리(Spring Boot 스타터 없이 core만). 색인 어댑터(imperative)에서만 씁니다.
//
// ES가 지속 실패하면 서킷이 열려, 배치마다 재시도(DefaultErrorHandler의 backoff)로 시간을 낭비하는 대신 즉시
// 실패시킵니다. 그 예외는 리스너로 전파돼 기존 에러 핸들러가 재시도 후 board-changed-dlq로 격리합니다
// (파이프라인 동작은 그대로, 장애 시 낭비만 줄임).
@Configuration
class ResilienceConfig {
    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val config =
            CircuitBreakerConfig
                .custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build()
        return CircuitBreakerRegistry.of(config)
    }

    companion object {
        const val ELASTICSEARCH_INDEX = "elasticsearch-index"
    }
}
