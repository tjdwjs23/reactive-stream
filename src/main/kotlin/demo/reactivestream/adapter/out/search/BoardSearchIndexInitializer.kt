package demo.reactivestream.adapter.out.search

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.stereotype.Component

// 기동 시 'boards' 인덱스를 Nori 설정/매핑(@Setting/@Mapping)과 함께 생성합니다.
// 이미 있으면 건너뜁니다(멱등). ES가 떠 있지 않아도 앱 기동은 막지 않도록 실패는 로그만 남깁니다
// (조회수 Redis 증가와 같은 베스트에포트 철학) — ES 복구 후 재기동하거나 재색인으로 회복합니다.
@Component
class BoardSearchIndexInitializer(
    private val operations: ReactiveElasticsearchOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexIfAbsent() {
        try {
            runBlocking {
                val indexOps = operations.indexOps(BoardDocument::class.java)
                if (indexOps.exists().awaitSingle()) {
                    log.info("'boards' index already exists — skip creation")
                } else {
                    indexOps.createWithMapping().awaitSingle()
                    log.info("'boards' index created with Nori analyzer settings/mappings")
                }
            }
        } catch (e: Exception) {
            log.warn(
                "failed to initialize 'boards' index (is Elasticsearch up?); " +
                    "search will be unavailable until the index exists. cause={}",
                e.toString(),
            )
        }
    }
}
