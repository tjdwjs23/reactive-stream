// search-indexer = board-changed 이벤트를 소비해 Elasticsearch 색인을 갱신하는 독립 서비스.
// board-service와 물리적으로 분리되며, event-contract만 공유한다.
// Kafka 리스너는 board-service의 @Scheduled 배치와 같은 "blocking 드라이빙 어댑터" 성격이라,
// 어댑터 안에서 runBlocking으로 코루틴 경계로 브리지한다(프로젝트 공통 컨벤션).
// Kotlin JVM / ktlint / JDK21 툴체인은 루트 subprojects{} 컨벤션이 이미 적용한다.
plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":event-contract"))

    // 웹 계층 없이 뜨는 애플리케이션(컨슈머 워커). health 확인용 actuator만 얹는다.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kafka 소비. @KafkaListener 컨테이너가 별도 스레드에서 폴링하고, 어댑터가 runBlocking으로 브리지한다.
    implementation("org.springframework.kafka:spring-kafka")

    // 이벤트 JSON ↔ Kotlin data class 역직렬화(Instant 포함)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // java.time(Instant) (역)직렬화. 이게 있어야 JacksonAutoConfiguration이 jsr310 모듈을 등록한다.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
