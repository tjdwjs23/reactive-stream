package demo.board.indexer.adapter.`in`.messaging

import demo.board.events.BoardChangedEvent
import demo.board.indexer.application.port.`in`.ApplyBoardChangeUseCase
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.listener.BatchListenerFailedException
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// board-changed 토픽 컨슈머(드라이빙 어댑터). 웹 컨트롤러가 UseCase에 의존하듯, 이 어댑터도 UseCase에만 의존합니다.
//
// @KafkaListener 컨테이너는 자체 스레드에서 블로킹 폴링하므로(board-service의 @Scheduled 배치와 같은 성격),
// 여기서 역직렬화 후 유스케이스를 동기 호출합니다. 역직렬화는 Boot 4의 Jackson 3(tools.jackson) ObjectMapper로 합니다.
//
// 배치 소비: 한 poll로 받은 레코드들을 List로 받아 벌크로 색인합니다(KafkaConsumerConfig의 isBatchListener=true).
// 처리 불가(포이즌) 레코드는 조용히 유실시키지 않고 DLQ로 격리합니다 — DefaultErrorHandler + DeadLetterPublishingRecoverer.
@Component
class BoardChangedListener(
    private val objectMapper: ObjectMapper,
    private val applyBoardChangeUseCase: ApplyBoardChangeUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [BoardChangedEvent.TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onMessages(records: List<ConsumerRecord<String, String>>) {
        val events = ArrayList<BoardChangedEvent>(records.size)
        records.forEachIndexed { index, record ->
            val event =
                try {
                    objectMapper.readValue(record.value(), BoardChangedEvent::class.java)
                } catch (e: Exception) {
                    // 이 레코드는 역직렬화가 안 되는 포이즌 메시지 — 재시도해도 영원히 실패합니다.
                    // 앞서 정상 파싱된 이벤트는 먼저 반영해 유실을 막고(그 앞 오프셋은 커밋됨),
                    // BatchListenerFailedException(index)로 "이 레코드만" DLQ로 보냅니다. 뒤 레코드는 새 배치로 재전달됩니다.
                    if (events.isNotEmpty()) applyBoardChangeUseCase.applyAll(events)
                    log.error(
                        "board-changed 역직렬화 실패 → DLQ 격리 (partition={} offset={})",
                        record.partition(),
                        record.offset(),
                        e,
                    )
                    throw BatchListenerFailedException("board-changed 역직렬화 실패", e, index)
                }
            events.add(event)
        }

        applyBoardChangeUseCase.applyAll(events)
        log.info("board-changed 배치 색인 완료: {}건", events.size)
    }
}
