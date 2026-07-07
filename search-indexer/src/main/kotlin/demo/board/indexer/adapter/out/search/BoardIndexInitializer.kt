package demo.board.indexer.adapter.out.search

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Component

// 기동 시 'boards' 인덱스를 Nori 설정/매핑(@Setting/@Mapping)과 함께 멱등 생성합니다.
// search-indexer가 이 인덱스의 writer이므로 스키마 생성 책임도 이쪽에 둡니다(board-service는 reader).
// ES가 아직 안 떠 있어도 기동을 막지 않도록 실패는 로그만 남깁니다(베스트에포트) — ES 복구 후 재기동으로 회복.
@Component
class BoardIndexInitializer(
    private val operations: ElasticsearchOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexIfAbsent() {
        try {
            val indexOps = operations.indexOps(BoardDocument::class.java)
            if (indexOps.exists()) {
                log.info("'boards' index already exists — skip creation")
            } else {
                indexOps.createWithMapping()
                log.info("'boards' index created with Nori analyzer settings/mappings")
            }
        } catch (e: Exception) {
            log.warn(
                "failed to initialize 'boards' index (is Elasticsearch up?); " +
                    "indexing will fail until the index exists. cause={}",
                e.toString(),
            )
        }
    }
}
