package demo.reactivestream.config

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks

// Reactor 자동 컨텍스트 전파를 켭니다.
// WebFlux+코루틴에서는 요청이 스레드를 바꿔가며 처리되는데, 이 훅이 없으면 suspend/Flow 경계에서
// Micrometer Observation(추적 ID)·MDC 같은 컨텍스트가 유실될 수 있습니다.
// io.micrometer:context-propagation과 함께 동작해 ThreadLocal ↔ Reactor Context를 이어 줍니다.
@Configuration
class ObservabilityConfig {
    @PostConstruct
    fun enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation()
    }
}
