package demo.search.indexer

import demo.search.events.BoardChangeType
import demo.search.events.BoardChangedEvent
import demo.search.indexer.adapter.out.search.BoardDocument
import demo.search.indexer.support.ElasticsearchTestContainer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

// end-to-end 통합테스트: 인-JVM Kafka(@EmbeddedKafka) + 실제 ES(Nori)를 띄우고, search-service가 발행하는 것과 동일한
// board-changed 이벤트를 토픽에 넣어 search-indexer(@KafkaListener → 서비스 → ES) 전 구간을 검증합니다.
// 컨텍스트가 실제로 뜨므로 빈 wiring(Jackson3 ObjectMapper, ElasticsearchOperations, @EnableKafka 리스너)도 함께 검증됩니다.
//
// 임베디드 브로커의 주소를 앱의 spring.kafka.bootstrap-servers로 주입합니다(플레이스홀더는 브로커 기동 후 지연 해석).
@SpringBootTest
@EmbeddedKafka(topics = [BoardChangedEvent.TOPIC, "board-changed-dlq"], partitions = 1)
@TestPropertySource(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
class BoardChangedConsumerIT {
    @Autowired
    private lateinit var operations: ElasticsearchOperations

    // search-service와 동일한 Jackson 3 ObjectMapper로 이벤트를 직렬화해 실제 발행 페이로드를 재현합니다.
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

    @Test
    fun `역직렬화 불가한 포이즌 메시지는 board-changed-dlq로 격리된다`() {
        // 유효한 JSON이 아닌 메시지 — 아무리 재시도해도 역직렬화가 안 되므로 DLQ로 가야 한다(조용한 유실 금지).
        val poison = "{ 이건 유효한 JSON이 아닙니다"
        publishRaw(key = "9999", value = poison)

        dlqConsumer().use { consumer ->
            consumer.subscribe(listOf("board-changed-dlq"))
            // 에러 핸들러가 backoff로 몇 차례 재시도한 뒤 DLQ로 보내므로 여유 있게 대기한다.
            await().atMost(Duration.ofSeconds(30)).untilAsserted {
                val found =
                    consumer
                        .poll(Duration.ofMillis(500))
                        .any { it.value() == poison }
                assertTrue(found, "포이즌 메시지가 board-changed-dlq에 격리되어야 한다")
            }
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

    // search-service의 Kafka 프로듀서를 대신해, 같은 토픽/키(=boardId)/직렬화 규약으로 이벤트를 발행합니다.
    private fun publish(
        boardId: Long,
        event: BoardChangedEvent,
    ) = publishRaw(boardId.toString(), objectMapper.writeValueAsString(event))

    // board-changed 토픽에 원문 문자열을 그대로 발행합니다(포이즌 메시지 재현용).
    private fun publishRaw(
        key: String,
        value: String,
    ) {
        val props =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.brokersAsString)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            }
        KafkaProducer<String, String>(props).use { producer ->
            producer.send(ProducerRecord(BoardChangedEvent.TOPIC, key, value)).get()
        }
    }

    // board-changed-dlq를 처음부터 읽는 테스트용 컨슈머.
    private fun dlqConsumer(): KafkaConsumer<String, String> {
        val props =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.brokersAsString)
                put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-verify-${System.nanoTime()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            }
        return KafkaConsumer(props)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            ElasticsearchTestContainer.registerProperties(registry)
        }
    }
}
