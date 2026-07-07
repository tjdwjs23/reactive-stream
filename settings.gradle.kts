rootProject.name = "board-platform"

// 모노레포(Gradle 멀티모듈): reactive 게시판 서비스 + Kafka 소비 검색 색인 서비스.
// 두 서비스는 event-contract(순수 Kotlin, 프레임워크 무의존)를 컴파일 타임에 공유해
// 이벤트 스키마 드리프트를 빌드에서 차단한다. 배포는 서비스별 bootJar/Dockerfile로 독립.
include(":event-contract")
include(":board-service")
include(":search-indexer")
