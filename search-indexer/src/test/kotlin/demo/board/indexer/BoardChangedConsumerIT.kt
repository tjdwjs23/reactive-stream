package demo.board.indexer

import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import demo.board.indexer.adapter.out.search.BoardDocument
import demo.board.indexer.support.ElasticsearchTestContainer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Properties

// end-to-end 통합테스트: 인-JVM Kafka(@EmbeddedKafka) + 실제 ES(Nori)를 띄우고, board-service가 발행하는 것과 동일한
// board-changed 이벤트를 토픽에 넣어 search-indexer(@KafkaListener → 서비스 → ES) 전 구간을 검증합니다.
// 컨텍스트가 실제로 뜨므로 빈 wiring(Jackson3 ObjectMapper, ElasticsearchOperations, @EnableKafka 리스너)도 함께 검증됩니다.
//
// 임베디드 브로커의 주소를 앱의 spring.kafka.bootstrap-servers로 주입합니다(플레이스홀더는 브로커 기동 후 지연 해석).
@SpringBootTest
@EmbeddedKafka(topics = [BoardChangedEvent.TOPIC], partitions = 1)
@TestPropertySource(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
class BoardChangedConsumerIT {
    @Autowired
    private lateinit var operations: ElasticsearchOperations

    // board-service와 동일한 Jackson 3 ObjectMapper로 이벤트를 직렬화해 실제 발행 페이로드를 재현합니다.
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var embeddedKafka: EmbeddedKafkaBroker

    @Test
    fun `CREATED 이벤트를 소비해 ES boards 인덱스에 문서를 색인한다`() {
        val boardId = 7001L
        publish(
            boardId,
            createdEvent(boardId, title = "카카오메일 통합테스트 공지", content = "이벤트 기반 색인을 검증합니다.", viewCount = 3L),
        )

        // GET by _id는 ES에서 realtime이라 refresh를 기다릴 필요가 없습니다 — 소비 지연만 대기합니다.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val doc = operations.get(boardId.toString(), BoardDocument::class.java)
            assertNotNull(doc, "CREATED 이벤트 소비 후 문서가 색인되어야 한다")
            assertEquals("카카오메일 통합테스트 공지", doc!!.title)
            assertEquals("이벤트 기반 색인을 검증합니다.", doc.content)
            assertEquals(3L, doc.viewCount)
            assertEquals(7L, doc.authorId)
        }
    }

    @Test
    fun `DELETED 이벤트를 소비해 ES 색인에서 문서를 제거한다`() {
        val boardId = 7002L
        // 먼저 색인되어 있어야 함
        publish(boardId, createdEvent(boardId, title = "삭제 대상", content = "곧 삭제될 게시글입니다.", viewCount = 0L))
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertNotNull(operations.get(boardId.toString(), BoardDocument::class.java))
        }

        // DELETED 이벤트 발행 → 색인에서 사라져야 함
        publish(boardId, deletedEvent(boardId))
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertNull(
                operations.get(boardId.toString(), BoardDocument::class.java),
                "DELETED 이벤트 소비 후 문서가 색인에서 제거되어야 한다",
            )
        }
    }

    private fun createdEvent(
        boardId: Long,
        title: String,
        content: String,
        viewCount: Long,
    ) = BoardChangedEvent(
        eventId = "it-created-$boardId",
        boardId = boardId,
        type = BoardChangeType.CREATED,
        title = title,
        content = content,
        authorId = 7L,
        viewCount = viewCount,
        createdAt = LocalDateTime.of(2026, 7, 7, 10, 0),
        occurredAt = Instant.parse("2026-07-07T01:00:00Z"),
    )

    private fun deletedEvent(boardId: Long) =
        BoardChangedEvent(
            eventId = "it-deleted-$boardId",
            boardId = boardId,
            type = BoardChangeType.DELETED,
            occurredAt = Instant.parse("2026-07-07T01:00:00Z"),
        )

    // board-service의 Kafka 프로듀서를 대신해, 같은 토픽/키(=boardId)/직렬화 규약으로 이벤트를 발행합니다.
    private fun publish(
        boardId: Long,
        event: BoardChangedEvent,
    ) {
        val props =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.brokersAsString)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            }
        KafkaProducer<String, String>(props).use { producer ->
            producer
                .send(
                    ProducerRecord(BoardChangedEvent.TOPIC, boardId.toString(), objectMapper.writeValueAsString(event)),
                ).get()
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            ElasticsearchTestContainer.registerProperties(registry)
        }
    }
}
