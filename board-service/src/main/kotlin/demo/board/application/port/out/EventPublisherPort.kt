package demo.board.application.port.out

// 이벤트를 메시지 브로커로 발행하는 out-port. 구체 기술(Kafka)은 어댑터가 감춥니다.
// key는 파티셔닝 기준(같은 key는 같은 파티션 → 순서 보장), payload는 직렬화된 이벤트 원문입니다.
interface EventPublisherPort {
    suspend fun publish(
        topic: String,
        key: String,
        payload: String,
    )
}
