package demo.board.indexer.application.port.out

// 색인 파이프라인의 "운영 사건"을 관측 백엔드에 기록하는 out-port입니다.
//
// board-service의 ObservabilityPort와 같은 규칙을 따릅니다 — 이 인터페이스는 프레임워크에 무의존하고,
// Micrometer/OTel 같은 구체 기술은 어댑터(MicrometerIndexerObservabilityAdapter)만 압니다. 서비스/설정은
// "문서 N건을 색인했다 / 메시지 1건을 DLQ로 격리했다" 같은 운영 어휘로만 호출합니다.
//
// Kafka 컨슈머 랙(kafka.consumer.*)은 유입이 얼마나 밀리는지를 보여주지만, "실제로 초당 몇 건을 색인/삭제했고
// 배치 한 번이 ES에 쓰는 데 얼마나 걸렸으며, 포이즌 메시지가 몇 건이나 DLQ로 빠졌는지"는 이 파이프라인 안에서만
// 알 수 있어 이 포트로 노출합니다(랙만으로는 색인 병목·조용한 유실을 구분하지 못함).
//
// 모든 메서드는 인메모리 카운터/타이머 조작(논블로킹, I/O 없음)이라 suspend가 아닙니다. 기록은 베스트에포트
// 부수효과이며, 컨슈머 스레드에서 동기로 호출됩니다.
interface IndexerObservabilityPort {
    // 배치의 ES 쓰기(upsert+delete)를 감싸 소요 시간을 타이머로 기록합니다. 예외가 나도 시도 지연은 기록됩니다.
    fun recordIndexingBatch(block: () -> Unit)

    // 색인(upsert)에 성공적으로 반영된 문서 수.
    fun boardsIndexed(count: Int)

    // 색인에서 삭제된 문서 수.
    fun boardsDeleted(count: Int)

    // 재시도 후에도 처리 불가해 board-changed-dlq로 격리된 메시지 1건. 0보다 크면 조용한 유실이 아니라
    // "격리된 포이즌"이 존재한다는 신호 — 알림/조사 트리거의 근거가 됩니다.
    fun messageDeadLettered()
}
