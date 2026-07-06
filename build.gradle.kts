plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    // 코드 커버리지(Kotlin 네이티브). ./gradlew koverHtmlReport / koverVerify
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "demo.board"
version = "0.0.1-SNAPSHOT"
description = "reactive"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
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

    // 관측성: Actuator(health/metrics + r2dbc health), Micrometer Prometheus 레지스트리(처리량/지연 노출)
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Reactor ↔ 코루틴 경계에서 Observation/MDC 등 컨텍스트 자동 전파
    implementation("io.micrometer:context-propagation")

    // API 문서 자동화: springdoc-openapi(WebFlux). /swagger-ui.html + /v3/api-docs 제공.
    // v3.x가 Spring Boot 4 / Spring Framework 7 지원 라인입니다(2.x는 Boot 4에서 동작하지 않음).
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")

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

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

ktlint {
    version.set("1.5.0")
    debug.set(false)
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    filter {
        exclude("**/generated/**")
    }
}

kover {
    reports {
        filters {
            excludes {
                // 부트스트랩 진입점(main)은 테스트 대상이 아니므로 커버리지 집계에서 제외합니다.
                classes("demo.board.BoardApplication*")
            }
        }
        // 회귀 방지 게이트. 현재 라인 커버리지(~94%)보다 여유를 둔 하한선(85%)을 강제합니다.
        // ./gradlew koverVerify 로 검증, koverHtmlReport 로 리포트 확인.
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
