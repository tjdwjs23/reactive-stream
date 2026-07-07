// board-service = reactive 게시판 애플리케이션(정본 DB) + Outbox 프로듀서.
// Kotlin JVM / ktlint / JDK21 툴체인은 루트 subprojects{} 컨벤션이 이미 적용한다.
plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    // 공유 이벤트 계약(순수 Kotlin). board-changed 토픽 페이로드/토픽명을 여기서 가져온다.
    implementation(project(":event-contract"))

    // webflux/data-r2dbc 스타터가 spring-boot-starter(core)를 이미 포함하므로 별도 선언하지 않습니다.
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // WebFlux/R2DBC 리액티브 타입(Mono/Flux) ↔ 코루틴(suspend/Flow) 브리지
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.9.0")

    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")

    // 인증/인가: 리액티브 Spring Security + 자체 발급 JWT.
    // resource-server 스타터가 리액티브 JWT 디코딩과 spring-security-oauth2-jose(NimbusJwtEncoder/Decoder)를 함께 가져옵니다.
    // 별도 JWT 라이브러리 없이 Spring Security 자체 지원(HS256 대칭키)으로 발급/검증합니다.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // 조회수 카운터: 리액티브 Redis(Lettuce, 논블로킹). 블로킹 RedisTemplate/Jedis는 쓰지 않습니다.
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // 한글 전문검색: Elasticsearch(Nori 형태소 분석). WebFlux가 클래스패스에 있으므로
    // 리액티브 클라이언트(ReactiveElasticsearchClient/Operations)가 자동 구성됩니다. 블로킹 클라이언트는 쓰지 않습니다.
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // 관측성. Actuator(health + r2dbc/redis/es 헬스, /actuator/metrics).
    // 메트릭 저장/조회는 Mimir가 담당하고, 앱은 OTLP로 push합니다(아래 opentelemetry 스타터).
    // 스크레이프 파이프라인이 없어(Alloy는 OTLP 수신만) Prometheus 레지스트리는 두지 않습니다.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Reactor ↔ 코루틴 경계에서 Observation/MDC 등 컨텍스트 자동 전파
    implementation("io.micrometer:context-propagation")

    // 분산 트레이싱 + 메트릭을 모두 OTLP로 push하는 LGTM 파이프라인.
    // Spring Boot 4는 트레이싱 자동구성을 전용 모듈로 분리했고, 이 스타터가 그 자동구성
    // (spring-boot-micrometer-tracing[-opentelemetry], spring-boot-opentelemetry)과 OTLP span exporter,
    // 그리고 OTLP '메트릭' 레지스트리(micrometer-registry-otlp)를 함께 가져옵니다.
    // (Boot 3.x식 micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp 조합은 Boot 4에선
    //  자동구성이 딸려오지 않아 Tracer가 활성화되지 않습니다.)
    // → metrics/logs/traces를 모두 OTLP로 단일 수집기 Grafana Alloy(:4318)에 push하고, Alloy가
    //   Mimir/Loki/Tempo로 팬아웃합니다(application.yml + monitoring/alloy/config.alloy).
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // 로그도 OTLP로 내보내 3종 신호(metrics/logs/traces)를 단일 파이프라인(→ Grafana Alloy)으로 통합합니다.
    // 이 appender가 logback 로그를 OTel LogRecord로 변환하고, Boot가 구성한 OTLP log exporter가 Alloy로 push합니다.
    // (Boot는 이 appender를 자동 부착하지 않으므로 OpenTelemetryAppender.install(...)을 기동 시 호출 — config 참고.)
    // BOM 관리 밖이라 버전을 명시합니다(-alpha 라인이 최신 안정 배포 관례).
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.21.0-alpha")

    // API 문서 자동화: springdoc-openapi(WebFlux). /swagger-ui.html + /v3/api-docs 제공.
    // v3.x가 Spring Boot 4 / Spring Framework 7 지원 라인입니다(2.x는 Boot 4에서 동작하지 않음).
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")

    // 이벤트 발행(Kafka). Transactional Outbox 릴레이가 board-changed 토픽으로 발행합니다.
    // KafkaTemplate은 지연 연결이라 브로커가 없어도 부팅되며, 발행 시도 시에만 연결합니다.
    // KafkaTemplate.send가 돌려주는 CompletableFuture는 kotlinx.coroutines.future.await로 코루틴에 잇습니다
    // (jdk8 통합은 1.6.0부터 coroutines-core에 병합돼 별도 의존성이 필요 없습니다).
    implementation("org.springframework.kafka:spring-kafka")

    // R2DBC 드라이버: 애플리케이션 런타임 DB 접근용(JDBC 드라이버는 사용하지 않음)
    runtimeOnly("org.postgresql:r2dbc-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
    testImplementation("io.mockk:mockk:1.13.13")
    // 리액티브 보안 테스트 지원(mockUser/JWT mutator 등)
    testImplementation("org.springframework.security:spring-security-test")
    // 아키텍처 규칙(헥사고날 의존성 방향·계층 순수성)을 테스트로 강제 (Kotlin 네이티브)
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-elasticsearch")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ES Testcontainer가 빌드하는 Nori Dockerfile 경로(docker/elasticsearch/Dockerfile)는 모노레포 루트 기준입니다
// (docker-compose의 빌드 컨텍스트와 동일 표기). Gradle 기본 테스트 작업 디렉터리는 서브모듈(board-service)이라,
// 루트로 맞춰 그 상대경로가 재구성 이전과 동일하게 해석되게 합니다.
tasks.withType<Test> {
    workingDir = rootProject.projectDir
}

// Spring Boot 앱 모듈은 실행 가능한 bootJar만 산출물로 씁니다. 중복되는 plain jar(-plain.jar)는 비활성화해
// build/libs에 jar가 하나만 남게 합니다(Dockerfile이 단일 jar를 안전하게 COPY).
tasks.named<Jar>("jar") { enabled = false }

kover {
    reports {
        filters {
            excludes {
                // 부트스트랩 진입점(main)은 테스트 대상이 아니므로 커버리지 집계에서 제외합니다.
                classes("demo.board.BoardApplication*")
            }
        }
        // 회귀 방지 게이트. 현재 라인 커버리지(~94%)에 바짝 붙인 하한선(92%)을 강제합니다.
        // 게이트가 실측보다 크게 낮으면 커버리지 회귀를 놓치므로, 실측에 근접하게 조입니다.
        // ./gradlew koverVerify 로 검증, koverHtmlReport 로 리포트 확인.
        verify {
            rule {
                minBound(92)
            }
        }
    }
}
