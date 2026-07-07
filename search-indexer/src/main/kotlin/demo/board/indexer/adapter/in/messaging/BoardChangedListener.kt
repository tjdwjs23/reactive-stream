package demo.board.indexer.adapter.`in`.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import demo.board.events.BoardChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * board-changed 토픽 컨슈머(드라이빙 어댑터).
 *
 * @KafkaListener 컨테이너는 자체 스레드에서 폴링하는 blocking 세계다 — board-service의 @Scheduled 배치와
 * 같은 성격이라, 코루틴이 필요한 후속 색인 처리는 어댑터 안에서 runBlocking으로 브리지한다(프로젝트 공통 컨벤션).
 *
 * 현재는 수신·역직렬화·로그까지. 다음 단계에서 ES 색인 out-port 호출을 여기에 연결한다.
 */
@Component
class BoardChangedListener(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [BoardChangedEvent.TOPIC], groupId = "\${spring.kafka.consumer.group-id}")
    fun onMessage(payload: String) {
        val event = objectMapper.readValue(payload, BoardChangedEvent::class.java)
        log.info(
            "board-changed 수신: eventId={} boardId={} type={}",
            event.eventId,
            event.boardId,
            event.type,
        )
        // TODO(next): BoardSearchPort로 색인 갱신(CREATED/UPDATED) / 삭제(DELETED) — 멱등 처리(eventId)
    }
}
