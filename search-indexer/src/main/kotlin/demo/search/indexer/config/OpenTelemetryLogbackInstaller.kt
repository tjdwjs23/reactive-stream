package demo.search.indexer.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

// OTel logback appender(logback-spring.xml의 OTEL)에 실제 OpenTelemetry 인스턴스를 주입합니다(search-service와 동일).
// Boot는 이 appender를 자동 부착하지 않으므로, 컨텍스트 기동 시 install을 호출해야 버퍼링된 로그가 OTLP로 흘러갑니다.
// test 프로필에서는 OTEL appender/OpenTelemetry 빈이 없을 수 있으므로 ObjectProvider로 있을 때만 설치합니다.
@Component
class OpenTelemetryLogbackInstaller(
    private val openTelemetryProvider: ObjectProvider<OpenTelemetry>,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val openTelemetry = openTelemetryProvider.ifAvailable ?: return
        OpenTelemetryAppender.install(openTelemetry)
    }
}
