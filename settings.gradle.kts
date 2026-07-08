// JDK 25 LTS 툴체인을 로컬에 없으면 Gradle이 자동으로 내려받도록 하는 foojay 리졸버.
// (개발자가 JDK 25를 수동 설치하지 않아도 `./gradlew`가 툴체인을 프로비저닝 — CI/온보딩 마찰 감소.)
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "search-platform"

// 모노레포(Gradle 멀티모듈): 게시판 서비스 + Kafka 소비 검색 색인 서비스.
// 두 서비스는 event-contract(순수 Kotlin, 프레임워크 무의존)를 컴파일 타임에 공유해
// 이벤트 스키마 드리프트를 빌드에서 차단한다. 배포는 서비스별 bootJar/Dockerfile로 독립.
include(":event-contract")
include(":search-service")
include(":search-indexer")
