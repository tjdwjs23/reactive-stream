// 루트 빌드 = 모노레포 공통 컨벤션. 실제 애플리케이션 코드는 각 서브모듈에 있다.
// 플러그인 버전은 여기서 한 번만 선언(apply false)하고, 각 모듈이 버전 없이 apply 한다.
plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    // JPA @Entity에 no-arg 생성자 + allopen(프록시 상속 가능)을 자동 부여. R2DBC→JPA 전환으로 추가.
    kotlin("plugin.jpa") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
}

allprojects {
    group = "demo.board"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// 모든 서브모듈에 공통 적용: Kotlin JVM(JDK 25 LTS) + ktlint 코드 스타일 게이트.
// JDK 25 LTS로 올린 핵심 이유: 가상 스레드(Virtual Threads)의 캐리어 스레드 피닝이 JDK 24(JEP 491)에서
// 제거돼 25 LTS로 안정화됐습니다. synchronized 블록 안의 블로킹이 더 이상 플랫폼 스레드를 고정하지 않으므로,
// 블로킹 스택(MVC + JPA)이 가상 스레드 위에서 리액티브에 준하는 확장성을 얻습니다(readme의 전환 근거 참고).
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            // 런타임/툴체인은 JDK 25지만, Kotlin 2.2.x가 아직 바이트코드 타깃 25를 지원하지 않아 24로 맞춥니다
            // (Java 컴파일과 타깃을 일치시켜 "Inconsistent JVM target" 오류 방지). 가상 스레드·JEP 491(피닝 해결)은
            // 런타임 기능이라, JDK 25에서 실행되면 바이트코드 타깃과 무관하게 그대로 적용됩니다.
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    // Java 컴파일도 바이트코드 24로 맞춥니다(Kotlin 타깃과 일치). 툴체인(JDK 25)에서 --release 24로 컴파일합니다.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(24)
    }

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.5.0")
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        enableExperimentalRules.set(false)
        filter {
            exclude("**/generated/**")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
