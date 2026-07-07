// search-indexer = board-changed 이벤트를 소비해 Elasticsearch 색인을 갱신하는 독립 서비스.
// board-service와 물리적으로 분리되며, event-contract만 공유한다.
// @KafkaListener는 컨테이너 스레드에서 동기로 처리하고 ES 접근도 imperative라, 코루틴 브리지 없이 동작한다.
// Kotlin JVM / ktlint / JDK21 툴체인은 루트 subprojects{} 컨벤션이 이미 적용한다.
plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":event-contract"))

    // 컨슈머 워커지만, k8s liveness/readiness가 /actuator/health를 HTTP로 확인해야 하므로 경량 웹 서버(WebFlux/Netty)를 얹습니다.
    // (웹 스타터가 없으면 HTTP 서버가 안 떠 actuator가 노출되지 않고 프로브가 영원히 실패합니다.)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // 관측성(board-service와 동일한 OTLP 파이프라인 → Grafana Alloy). 이 서비스도 metrics/logs/traces를 push해
    // 분산 시스템의 first-class citizen이 됩니다. starter-opentelemetry가 Tracer + OTLP exporter + micrometer-registry-otlp를,
    // logback-appender가 로그→OTel LogRecord 변환을 제공합니다. @KafkaListener observation의 트레이스도 이 위에서 export됩니다.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.21.0-alpha")

    // Elasticsearch 색인 writer. 컨슈머 스레드가 블로킹이므로 imperative ElasticsearchOperations를 씁니다
    // (webflux가 있어도 imperative 템플릿 빈은 그대로 제공됨 — board-service와 동일 조합). 이쪽은 색인 전용.
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // Kafka 소비. Boot 4는 spring-kafka만으론 자동 구성이 안 딸려오므로 KafkaConsumerConfig에서 직접 구성한다.
    implementation("org.springframework.kafka:spring-kafka")

    // 회복탄력성(Resilience4j 2.x core). 색인 어댑터가 imperative(컨슈머 스레드 블로킹)라 코틀린/리액터 확장 없이
    // core만으로 충분합니다. ES 장애가 지속되면 서킷을 열어 배치마다 재시도를 낭비하지 않고 즉시 실패 →
    // DefaultErrorHandler가 재시도 후 DLQ 격리(기존 파이프라인과 정합).
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")

    // 이벤트 JSON 역직렬화. Boot 4는 Jackson 3(tools.jackson) 기반이며, ObjectMapper 자동 구성과 jackson-databind는
    // 위 spring-boot-starter-webflux가 가져오는 starter-json으로 이미 충족됩니다. 여기선 Kotlin data class 역직렬화용
    // Jackson 3 Kotlin 모듈만 더합니다(java.time은 Jackson 3에 기본 내장).
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
    // end-to-end 통합테스트: 인-JVM Kafka(@EmbeddedKafka, spring-kafka-test) + 실제 ES(Nori, Testcontainers)로
    // 이벤트 발행→소비→색인 전 구간을 검증. Kafka는 컨테이너 대신 임베디드 브로커라 빠르고 버전 궁합 이슈가 없다.
    testImplementation("org.testcontainers:testcontainers-elasticsearch")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ES Testcontainer가 빌드하는 Nori Dockerfile 경로(docker/elasticsearch/Dockerfile)는 모노레포 루트 기준입니다.
// Gradle 기본 테스트 작업 디렉터리는 서브모듈이라, 루트로 맞춰 상대경로가 그대로 해석되게 합니다(board-service와 동일).
tasks.withType<Test> {
    workingDir = rootProject.projectDir
}

// Spring Boot 앱 모듈은 실행 가능한 bootJar만 산출물로 씁니다. 중복되는 plain jar(-plain.jar)는 비활성화합니다.
tasks.named<Jar>("jar") { enabled = false }
