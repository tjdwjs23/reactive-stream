package demo.search.events

import java.time.Instant
import java.time.LocalDateTime

/**
 * search-service의 상품(Product) 상태 변화를 검색 색인 소비자(search-indexer)에게 알리는 도메인 이벤트.
 *
 * BoardChangedEvent와 같은 Transactional Outbox → Kafka(`product-changed`) → search-indexer 파이프라인을 타지만,
 * 상품은 짧은 이름의 초성/자동완성 검색이 목적이라 별도 인덱스(`products`)·별도 토픽으로 분리한다.
 * BoardChangedEvent와 굳이 공통 상위 타입으로 묶지 않는다 — 두 도메인의 필드가 달라 병렬 계약이 더 명확하다.
 *
 * 순수 Kotlin data class — 직렬화(JSON)·전송(Kafka)에 대한 지식은 어댑터가 가진다.
 */
data class ProductChangedEvent(
    /** 이벤트 고유 id(프로듀서가 발행 시 부여). 아웃박스 행의 키로 저장된다. */
    val eventId: String,
    /** 대상 상품 id. Kafka 메시지 key로도 쓰여 같은 상품 이벤트의 파티션 순서를 보장한다. */
    val productId: Long,
    val type: ProductChangeType,
    /** CREATED/UPDATED에서 채워진다. DELETED에서는 null(색인에서 제거만 하면 되므로). */
    val name: String? = null,
    /** 상품 가격(원). CREATED/UPDATED에서 채워진다. */
    val price: Long? = null,
    /** 상품 생성 시각(CREATED/UPDATED에서 채워짐). 소비자가 존 변환 없이 그대로 색인하도록 LocalDateTime. */
    val createdAt: LocalDateTime? = null,
    /** 이 변경이 발생/기록된 시각(실제 instant). */
    val occurredAt: Instant,
) {
    companion object {
        /** Kafka 토픽명. 프로듀서/컨슈머가 이 상수를 공유해 토픽명 오타로 인한 단절을 막는다. */
        const val TOPIC = "product-changed"
    }
}

enum class ProductChangeType {
    CREATED,
    UPDATED,
    DELETED,
}
