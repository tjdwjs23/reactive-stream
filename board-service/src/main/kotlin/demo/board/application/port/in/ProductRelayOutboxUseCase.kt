package demo.board.application.port.`in`

// 상품 아웃박스(product_outbox)의 미발행 이벤트를 Kafka(product-changed)로 밀어내는 유스케이스.
// board의 RelayOutboxUseCase와 분리해(별도 테이블·토픽·게이지) 두 도메인 릴레이를 독립 운영합니다.
// 결과 타입은 board와 동일한 의미라 RelayResult를 재사용합니다.
interface ProductRelayOutboxUseCase {
    fun relay(): RelayResult
}
