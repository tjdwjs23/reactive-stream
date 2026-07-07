package demo.board.application.port.out

import demo.board.events.BoardChangedEvent

// 게시글 변경 이벤트를 아웃박스에 기록하는 out-port.
// 서비스는 게시글 쓰기와 "같은 트랜잭션 안에서" 이 record()를 호출해, DB 반영과 이벤트 기록을 원자적으로 묶습니다
// (Transactional Outbox). 직렬화·저장 매체(테이블)는 어댑터가 감춥니다 — 서비스는 "이벤트가 일어났다"만 압니다.
interface BoardEventOutboxPort {
    suspend fun record(event: BoardChangedEvent)
}
