package demo.search.indexer.adapter.out.search

import demo.search.indexer.application.port.out.BoardIndexPort
import demo.search.indexer.config.ResilienceConfig
import demo.search.indexer.domain.IndexedBoard
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Component

// BoardIndexPort의 Elasticsearch 구현(imperative). 컨슈머 스레드가 블로킹이므로 리액티브 대신 동기 Operations를 씁니다.
// saveAll은 _id(=게시글 id) 기준 upsert라 CREATED/UPDATED 모두 같은 문서를 덮어쓰고, 삭제는 없으면 무시됩니다(멱등).
// 도메인 IndexedBoard ↔ ES BoardDocument 변환은 이 어댑터 안에서만 일어납니다.
//
// 서킷브레이커: ES가 지속 실패하면 서킷이 열려 즉시 실패합니다(CallNotPermittedException). 이 예외는 리스너로
// 전파돼 DefaultErrorHandler가 재시도 후 DLQ로 격리하므로, 장애 중 배치마다 backoff 재시도를 낭비하지 않습니다.
@Component
class ElasticsearchBoardIndexAdapter(
    private val operations: ElasticsearchOperations,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : BoardIndexPort {
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ELASTICSEARCH_INDEX)

    // 벌크 upsert: save(Iterable)는 한 번의 ES bulk 요청으로 전 문서를 색인해 건별 왕복을 없앱니다(처리량 핵심 경로).
    override fun saveAll(boards: List<IndexedBoard>) {
        if (boards.isEmpty()) return
        breaker.executeRunnable { operations.save(boards.map { it.toDocument() }) }
    }

    // 삭제(DELETED)는 upsert보다 훨씬 드물어 id별로 제거합니다. 없는 id는 예외 없이 무시됩니다(멱등).
    override fun deleteAllById(boardIds: List<Long>) {
        breaker.executeRunnable {
            boardIds.forEach { operations.delete(it.toString(), BoardDocument::class.java) }
        }
    }

    private fun IndexedBoard.toDocument(): BoardDocument =
        BoardDocument(
            id = id.toString(),
            title = title,
            content = content,
            createdAt = createdAt,
            viewCount = viewCount,
            authorId = authorId,
        )
}
