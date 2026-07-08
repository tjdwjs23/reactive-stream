package demo.search.application.port.out

import demo.search.events.BoardChangedEvent

// 게시글 변경 이벤트를 아웃박스에 기록하는 out-port.
// 서비스는 게시글 쓰기와 "같은 트랜잭션 안에서" 이 record()를 호출해, DB 반영과 이벤트 기록을 원자적으로 묶습니다
// (Transactional Outbox). 직렬화·저장 매체(테이블)는 어댑터가 감춥니다 — 서비스는 "이벤트가 일어났다"만 압니다.
interface BoardEventOutboxPort {
    fun record(event: BoardChangedEvent)

    // 여러 이벤트를 한 번의 벌크 INSERT로 기록합니다. 아카이브 삭제/조회수 플러시처럼 한 트랜잭션에서
    // 다건의 이벤트를 남기는 배치 경로가 건별 왕복 없이 원자적으로 기록하기 위한 확장입니다
    // (단건 record와 동일하게 반드시 DB 변경과 "같은 트랜잭션" 안에서 호출됩니다).
    fun recordAll(events: List<BoardChangedEvent>)
}
