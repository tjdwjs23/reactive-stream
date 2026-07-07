// 루트 빌드 = 모노레포 공통 컨벤션. 실제 애플리케이션 코드는 각 서브모듈에 있다.
// 플러그인 버전은 여기서 한 번만 선언(apply false)하고, 각 모듈이 버전 없이 apply 한다.
plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
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

// 모든 서브모듈에 공통 적용: Kotlin JVM(JDK 21) + ktlint 코드 스타일 게이트.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
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
