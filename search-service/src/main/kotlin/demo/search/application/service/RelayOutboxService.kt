package demo.search.application.service

import demo.search.application.port.`in`.RelayOutboxUseCase
import demo.search.application.port.`in`.RelayResult
import demo.search.application.port.out.EventPublisherPort
import demo.search.application.port.out.ObservabilityPort
import demo.search.application.port.out.OutboxRelayPort
import demo.search.events.BoardChangedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

// 아웃박스 릴레이. 미발행 이벤트를 id 순으로 읽어 브로커에 발행하고, 성공분만 발행 완료로 표시합니다.
//
// 순서 보존이 핵심입니다 — 한 건이라도 발행에 실패하면 그 뒤(더 최신) 이벤트를 발행하지 않고 멈춥니다.
// 그래야 같은 게시글의 이벤트가 순서를 건너뛰지 않고, 다음 사이클에서 실패 지점부터 다시 시도합니다.
// 발행은 성공했는데 markPublished 전에 죽어도, 다음 사이클이 재발행합니다(at-least-once) —
// 소비자가 event_id/boardId upsert로 멱등 처리하므로 안전합니다.
//
// 스케줄러(@Scheduled)가 주기적으로 구동하며, 블로킹으로 동작합니다(코루틴 불필요 — 순차 발행이라 동시성 이득이 없음).
@Service
class RelayOutboxService(
    private val outboxRelayPort: OutboxRelayPort,
    private val eventPublisherPort: EventPublisherPort,
    // 미발행 백로그를 사이클마다 게이지로 보고합니다(board.outbox.unpublished). 릴레이가 밀리는지 관측하는 핵심 SLI.
    private val observability: ObservabilityPort,
    @Value("\${search.outbox.relay.batch-size:500}") private val batchSize: Int,
) : RelayOutboxUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun relay(): RelayResult {
        val batch = outboxRelayPort.readUnpublished(batchSize)
        if (batch.isEmpty()) {
            // 발행할 게 없어도 백로그는 0으로 갱신해, 직전에 밀렸던 게이지가 회복됐음을 반영합니다.
            observability.updateOutboxBacklog(0)
            return RelayResult(0)
        }

        val publishedIds = ArrayList<Long>(batch.size)
        for (record in batch) {
            try {
                eventPublisherPort.publish(BoardChangedEvent.TOPIC, record.partitionKey, record.payload)
                publishedIds.add(record.id)
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
        // 이번 사이클 반영 후 남은 백로그를 게이지에 보고합니다(따라잡았으면 0, 밀렸으면 잔여분).
        reportBacklog()
        return RelayResult(publishedIds.size)
    }

    // 백로그 조회 실패가 릴레이 자체를 깨지 않도록 베스트에포트로 감쌉니다(메트릭은 부수효과).
    private fun reportBacklog() {
        try {
            observability.updateOutboxBacklog(outboxRelayPort.countUnpublished())
        } catch (e: Exception) {
            log.warn("failed to report outbox backlog gauge; skipping this cycle. cause={}", e.toString())
        }
    }
}
