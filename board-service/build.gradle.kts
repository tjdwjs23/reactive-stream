// board-service = 게시판 애플리케이션(정본 DB) + Outbox 프로듀서.
// 스택: Spring MVC + 가상 스레드(JDK 25) + Spring Data JPA(+ Kotlin JDSL) + Flyway.
// 코루틴은 아카이브 배치의 구조적 동시성(Channel 팬아웃)에만 남긴다(웹/서비스는 블로킹).
// Kotlin JVM / ktlint / JDK25 툴체인은 루트 subprojects{} 컨벤션이 이미 적용한다.
plugins {
    kotlin("plugin.spring")
    // JPA @Entity에 no-arg 생성자 + allopen을 부여(Hibernate 프록시). R2DBC→JPA 전환으로 추가.
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    // 공유 이벤트 계약(순수 Kotlin). board-changed 토픽 페이로드/토픽명을 여기서 가져온다.
    implementation(project(":event-contract"))

    // Spring MVC(Tomcat). 가상 스레드(spring.threads.virtual.enabled=true, application.yml)를 켜면 요청마다
    // 가상 스레드가 배정돼, 블로킹 I/O(JPA/Redis/ES) 동안 플랫폼 스레드를 점유하지 않고 높은 동시성을 냅니다.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // 코루틴은 아카이브 배치의 Channel 백프레셔 팬아웃에만 사용(웹/서비스는 블로킹). 리액티브 브리지는 제거.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // 영속성: Spring Data JPA(Hibernate). 조회는 Kotlin JDSL(코드젠 없는 타입세이프 JPQL DSL)로 작성합니다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Kotlin JDSL — QueryDSL의 kapt 코드젠 없이 순수 Kotlin으로 동적/타입세이프 쿼리를 짭니다.
    implementation("com.linecorp.kotlin-jdsl:jpql-dsl:3.5.5")
    implementation("com.linecorp.kotlin-jdsl:jpql-render:3.5.5")
    implementation("com.linecorp.kotlin-jdsl:spring-data-jpa-support:3.5.5")
    // 스키마 마이그레이션(버전 관리). R2dbcSchemaInitializer(CREATE TABLE IF NOT EXISTS)를 대체합니다.
    // Boot 4는 자동설정을 기술별 모듈로 분리했으므로, Flyway 자동설정(기동 시 migrate 실행 + JPA보다 먼저 순서 보장)은
    // spring-boot-flyway가 제공합니다 — flyway-core 라이브러리만으로는 마이그레이션이 자동 실행되지 않습니다
    // (그러면 ddl-auto=validate가 "missing table"로 실패). flyway-database-postgresql는 PG 방언 모듈(Flyway 10+ 분리).
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // 인증/인가: (서블릿) Spring Security + 자체 발급 JWT.
    // resource-server 스타터가 JWT 디코딩과 spring-security-oauth2-jose(NimbusJwtEncoder/Decoder)를 함께 가져옵니다.
    // 별도 JWT 라이브러리 없이 Spring Security 자체 지원(HS256 대칭키)으로 발급/검증합니다.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // 조회수 카운터: Redis(Lettuce). MVC 스택이라 블로킹 StringRedisTemplate로 접근합니다.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 한글 전문검색: Elasticsearch(Nori 형태소 분석). MVC(비-webflux)라 블로킹 클라이언트
    // (ElasticsearchOperations/ElasticsearchClient)가 자동 구성됩니다.
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // 관측성. Actuator(health + db(JDBC)/redis/es 헬스, /actuator/metrics).
    // 메트릭 저장/조회는 Mimir가 담당하고, 앱은 OTLP로 push합니다(아래 opentelemetry 스타터).
    // 스크레이프 파이프라인이 없어(Alloy는 OTLP 수신만) Prometheus 레지스트리는 두지 않습니다.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // 스레드 경계(아카이브 배치의 Dispatchers.IO 홉 등)를 넘어 Observation/MDC(traceId) 컨텍스트 전파 — Micrometer Tracing이 사용
    implementation("io.micrometer:context-propagation")

    // 분산 트레이싱 + 메트릭을 모두 OTLP로 push하는 LGTM 파이프라인.
    // Spring Boot 4는 트레이싱 자동구성을 전용 모듈로 분리했고, 이 스타터가 그 자동구성
    // (spring-boot-micrometer-tracing[-opentelemetry], spring-boot-opentelemetry)과 OTLP span exporter,
    // 그리고 OTLP '메트릭' 레지스트리(micrometer-registry-otlp)를 함께 가져옵니다.
    // (Boot 3.x식 micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp 조합은 Boot 4에선
    //  자동구성이 딸려오지 않아 Tracer가 활성화되지 않습니다.)
    // → metrics/logs/traces를 모두 OTLP로 단일 수집기 Grafana Alloy(:4318)에 push하고, Alloy가
    //   Mimir/Loki/Tempo로 팬아웃합니다(application.yml + deploy/helm/board-platform/files/alloy/config.alloy).
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // 로그도 OTLP로 내보내 3종 신호(metrics/logs/traces)를 단일 파이프라인(→ Grafana Alloy)으로 통합합니다.
    // 이 appender가 logback 로그를 OTel LogRecord로 변환하고, Boot가 구성한 OTLP log exporter가 Alloy로 push합니다.
    // (Boot는 이 appender를 자동 부착하지 않으므로 OpenTelemetryAppender.install(...)을 기동 시 호출 — config 참고.)
    // BOM 관리 밖이라 버전을 명시합니다(-alpha 라인이 최신 안정 배포 관례).
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.21.0-alpha")

    // API 문서 자동화: springdoc-openapi(Spring MVC). /swagger-ui.html + /v3/api-docs 제공.
    // v3.x가 Spring Boot 4 / Spring Framework 7 지원 라인입니다(2.x는 Boot 4에서 동작하지 않음).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // 이벤트 발행(Kafka). Transactional Outbox 릴레이가 board-changed 토픽으로 발행합니다.
    // KafkaTemplate은 지연 연결이라 브로커가 없어도 부팅되며, 발행 시도 시에만 연결합니다.
    // KafkaTemplate.send가 돌려주는 CompletableFuture는 kotlinx.coroutines.future.await로 코루틴에 잇습니다
    // (jdk8 통합은 1.6.0부터 coroutines-core에 병합돼 별도 의존성이 필요 없습니다).
    implementation("org.springframework.kafka:spring-kafka")

    // 회복탄력성(Resilience4j 2.x — Spring Boot 스타터 없이 core만 사용). Boot4 자동구성 비호환을 피하고
    // 헥사고날 순수성을 지키기 위해 서킷브레이커를 "어댑터"에서만 프로그래매틱하게 적용합니다(application 계층은 무의존).
    // 블로킹 스택이라 executeSupplier/executeRunnable(동기 데코레이션)만 쓰므로 kotlin/reactor 모듈은 제거했습니다.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")

    // JDBC 드라이버: JPA(Hibernate) + Flyway가 사용합니다(R2DBC 드라이버는 제거).
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
    testImplementation("io.mockk:mockk:1.13.13")
    // 서블릿 보안 테스트 지원(MockMvc + SecurityMockMvcRequestPostProcessors: jwt()/user() 등)
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
