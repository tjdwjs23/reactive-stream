package demo.board.application.service

import demo.board.application.port.`in`.RelayOutboxUseCase
import demo.board.application.port.`in`.RelayResult
import demo.board.application.port.out.EventPublisherPort
import demo.board.application.port.out.OutboxRelayPort
import demo.board.events.BoardChangedEvent
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// 아웃박스 릴레이. 미발행 이벤트를 id 순으로 읽어 브로커에 발행하고, 성공분만 발행 완료로 표시합니다.
//
// 순서 보존이 핵심입니다 — 한 건이라도 발행에 실패하면 그 뒤(더 최신) 이벤트를 발행하지 않고 멈춥니다.
// 그래야 같은 게시글의 이벤트가 순서를 건너뛰지 않고, 다음 사이클에서 실패 지점부터 다시 시도합니다.
// 발행은 성공했는데 markPublished 전에 죽어도, 다음 사이클이 재발행합니다(at-least-once) —
// 소비자가 event_id로 멱등 처리하므로 안전합니다.
@Service
class RelayOutboxService(
    private val outboxRelayPort: OutboxRelayPort,
    private val eventPublisherPort: EventPublisherPort,
    @Value("\${board.outbox.relay.batch-size:100}") private val batchSize: Int = 100,
) : RelayOutboxUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun relay(): RelayResult {
        val batch = outboxRelayPort.readUnpublished(batchSize)
        if (batch.isEmpty()) return RelayResult(0)

        val publishedIds = ArrayList<Long>(batch.size)
        for (record in batch) {
            try {
                eventPublisherPort.publish(BoardChangedEvent.TOPIC, record.partitionKey, record.payload)
                publishedIds.add(record.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 실패 지점에서 멈춘다 — 뒤 이벤트를 앞질러 발행하면 순서가 깨진다. 다음 사이클에서 여기부터 재시도.
                log.warn(
                    "outbox relay stopped at record id={} (published {} so far); will retry next cycle. cause={}",
                    record.id,
                    publishedIds.size,
                    e.toString(),
                )
                break
            }
        }

        if (publishedIds.isNotEmpty()) outboxRelayPort.markPublished(publishedIds)
        return RelayResult(publishedIds.size)
    }
}
