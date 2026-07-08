package demo.board.application.port.out

import demo.board.events.ProductChangedEvent

// 상품 변경 이벤트를 아웃박스(product_outbox)에 기록하는 out-port.
// 서비스는 상품 쓰기와 "같은 트랜잭션 안에서" record()를 호출해 DB 반영과 이벤트 기록을 원자화합니다(Transactional Outbox).
interface ProductEventOutboxPort {
    fun record(event: ProductChangedEvent)
}

// 상품 아웃박스 릴레이 조회/표시 out-port. Board의 OutboxRelayPort와 형태는 같지만, 주입 모호성을 피하려 별도 인터페이스로 둡니다
// (같은 OutboxRecord DTO를 재사용). product_outbox를 폴링해 Kafka(product-changed)로 발행하는 릴레이가 씁니다.
interface ProductOutboxRelayPort {
    fun readUnpublished(limit: Int): List<OutboxRecord>

    fun markPublished(ids: List<Long>)

    fun countUnpublished(): Long
}
