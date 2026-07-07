package demo.board.indexer.application.port.`in`

import demo.board.events.BoardChangedEvent

// 게시글 변경 이벤트 한 건을 검색 인덱스에 반영하는 유스케이스.
// Kafka 컨슈머(드라이빙 어댑터)가 역직렬화한 이벤트를 이 포트로 넘긴다.
// 컨슈머 스레드가 블로킹 세계이므로 suspend가 아니다(ES 접근도 imperative).
interface ApplyBoardChangeUseCase {
    fun apply(event: BoardChangedEvent)
}
