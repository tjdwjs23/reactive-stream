// event-contract = search-service(프로듀서)와 search-indexer(컨슈머)가 공유하는 이벤트 계약.
// 순수 Kotlin만 담는다 — Spring/Jackson/Kafka 등 어떤 프레임워크에도 의존하지 않는다.
// (역)직렬화·전송은 각 서비스의 어댑터 책임이며, 이 모듈은 "무엇을 주고받는가"의 단일 진실원본이다.
// Kotlin JVM / ktlint / JDK25 툴체인은 루트 subprojects{} 컨벤션이 이미 적용한다.
