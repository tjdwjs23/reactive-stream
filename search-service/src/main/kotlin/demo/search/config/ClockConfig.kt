package demo.search.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

// 도메인/서비스가 "지금 시각"을 벽시계(LocalDateTime.now())에서 직접 읽지 않고 주입받도록 Clock을 빈으로 제공합니다.
// 이렇게 하면 생성 시각·아카이브 판정 같은 시간 의존 로직을 테스트에서 고정 Clock으로 결정적으로 검증할 수 있습니다.
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
