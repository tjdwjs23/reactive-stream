package demo.board.indexer.adapter.`in`.messaging

import demo.board.events.BoardChangedEvent
import demo.board.indexer.application.port.`in`.ApplyBoardChangeUseCase
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// board-changed 토픽 컨슈머(드라이빙 어댑터). 웹 컨트롤러가 UseCase에 의존하듯, 이 어댑터도 UseCase에만 의존합니다.
//
// @KafkaListener 컨테이너는 자체 스레드에서 블로킹 폴링하므로(board-service의 @Scheduled 배치와 같은 성격),
// 여기서 역직렬화 후 유스케이스를 동기 호출합니다. 역직렬화는 Boot 4의 Jackson 3(tools.jackson) ObjectMapper로 합니다.
@Component
class BoardChangedListener(
    private val objectMapper: ObjectMapper,
    private val applyBoardChangeUseCase: ApplyBoardChangeUseCase,
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
        applyBoardChangeUseCase.apply(event)
    }
}
