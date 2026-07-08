package demo.search.config

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

// Resilience4j 서킷브레이커 레지스트리를 명시적으로 구성합니다(Spring Boot 스타터 없이 core만 사용).
//
// 서킷브레이커는 "어댑터"에서만 프로그래매틱하게 적용합니다 — application/도메인 계층은 Resilience4j를 전혀 모릅니다
// (헥사고날 의존성 방향 유지). 외부 자원(Redis/Kafka/ES)이 반복 실패하면 서킷이 열려, 매 호출마다 타임아웃을
// 기다리는 대신 즉시 실패(CallNotPermittedException)합니다. 그러면:
//  - Redis(조회수 증가): 어댑터가 던진 예외를 BoardService가 이미 삼켜 DB 값으로 강등 응답합니다(폴백).
//  - Kafka(아웃박스 발행): 릴레이가 그 지점에서 멈추고 다음 사이클에 재시도합니다(순서 보존·유실 없음).
//  - ES(검색): 검색이 즉시 실패해 매달리지 않습니다.
@Configuration
class ResilienceConfig {
    // 모든 브레이커가 공유하는 기본 정책. 개별 튜닝이 필요하면 이름별 config로 확장할 수 있습니다.
    //  - COUNT 기반 슬라이딩 윈도우 20콜 중 실패율 50% 초과 시 OPEN.
    //  - OPEN 상태 10초 유지 후 HALF_OPEN으로 전환, 3콜을 시험 통과시켜 CLOSED 복귀 판단.
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
        // 어댑터가 레지스트리에서 브레이커를 꺼낼 때 쓰는 이름(오타로 인한 브레이커 분열을 막는 단일 소스).
        const val REDIS_VIEW_COUNT = "redis-viewcount"
        const val KAFKA_PUBLISHER = "kafka-publisher"
        const val ELASTICSEARCH_SEARCH = "elasticsearch-search"
    }
}
