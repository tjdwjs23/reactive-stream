package demo.board.events

import java.time.Instant
import java.time.LocalDateTime

/**
 * board-service의 게시글 상태 변화를 검색 색인 등 하위 소비자에게 알리는 도메인 이벤트.
 *
 * board-service(정본, R2DBC)와 search-indexer(ES)는 물리적으로 분리돼 있고, 이 이벤트가 둘을 잇는
 * 유일한 계약이다. Transactional Outbox로 게시글 쓰기와 원자적으로 기록된 뒤 Kafka(`board-changed`)로
 * 발행되며, 소비자는 이 페이로드만으로 색인을 갱신/삭제할 수 있어야 한다(추가 DB 조회 불필요).
 *
 * 순수 Kotlin data class — 직렬화 형식(JSON)이나 전송(Kafka)에 대한 지식은 어댑터가 가진다.
 */
data class BoardChangedEvent(
    /**
     * 이벤트 고유 id(프로듀서가 발행 시 부여). 아웃박스 행의 키로 저장된다.
     * 소비자 멱등 키로 쓸 수 있으나, 현재 유일한 소비자(search-indexer)는 eventId를 읽지 않고
     * ES `_id`(=boardId) 기반 upsert로 멱등을 달성한다.
     */
    val eventId: String,
    /** 대상 게시글 id. Kafka 메시지 key로도 쓰여 같은 게시글 이벤트의 파티션 순서를 보장한다. */
    val boardId: Long,
    val type: BoardChangeType,
    /** CREATED/UPDATED에서 채워진다. DELETED에서는 null(색인에서 제거만 하면 되므로). */
    val title: String? = null,
    val content: String? = null,
    val authorId: Long? = null,
    /** 색인 문서 복원용 조회수(쓰기 시점의 DB 누적값). 검색 히트에서 도메인 Board를 그대로 복원하는 데 쓰인다. */
    val viewCount: Long = 0,
    /**
     * 게시글 원본의 생성 시각(CREATED/UPDATED에서 채워짐).
     * 도메인 Board·색인 문서(BoardDocument)와 동일하게 LocalDateTime으로 두어, 소비자가 존 변환 없이 그대로 색인한다.
     */
    val createdAt: LocalDateTime? = null,
    /**
     * 이 변경이 발생/기록된 시각(실제 instant). 순서가 어긋난 이벤트 판별에 쓸 수 있으나,
     * 현재 소비자는 이를 읽지 않고 같은 파티션 도착 순서 + boardId별 last-write-wins로 순서를 처리한다.
     */
    val occurredAt: Instant,
) {
    companion object {
        /** Kafka 토픽명. 프로듀서/컨슈머가 이 상수를 공유해 토픽명 오타로 인한 단절을 막는다. */
        const val TOPIC = "board-changed"
    }
}

enum class BoardChangeType {
    CREATED,
    UPDATED,
    DELETED,
}
