package demo.search.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

// OTel logback appender(logback-spring.xml의 OTEL)에 실제 OpenTelemetry 인스턴스를 주입합니다.
// Boot는 이 appender를 자동 부착하지 않으므로(공식 문서 명시), 컨텍스트 기동 시 여기서 install을 호출해야
// appender가 그때까지 버퍼링해 둔 로그를 OTLP exporter로 흘려보냅니다.
//
// test 프로필에서는 OTEL appender 자체가 정의되지 않고 OpenTelemetry 빈도 없을 수 있으므로(export 비활성),
// ObjectProvider로 빈이 있을 때만 설치합니다 — 없으면 조용히 건너뜁니다(테스트 안전).
@Component
class OpenTelemetryLogbackInstaller(
    private val openTelemetryProvider: ObjectProvider<OpenTelemetry>,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val openTelemetry = openTelemetryProvider.ifAvailable ?: return
        OpenTelemetryAppender.install(openTelemetry)
    }
}
