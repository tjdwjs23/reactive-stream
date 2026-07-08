package demo.search.indexer.adapter.out.search

import demo.search.indexer.application.port.out.ProductIndexPort
import demo.search.indexer.config.ResilienceConfig
import demo.search.indexer.domain.IndexedProduct
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Component

// ProductIndexPort의 Elasticsearch 구현(imperative). ElasticsearchBoardIndexAdapter와 동일한 벌크 upsert/삭제 + 서킷브레이커.
// 쓰기는 alias('products')를 통해 현재 활성 버전 인덱스로 향합니다(_id = productId 기준 upsert라 멱등).
@Component
class ElasticsearchProductIndexAdapter(
    private val operations: ElasticsearchOperations,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : ProductIndexPort {
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ELASTICSEARCH_INDEX)

    override fun saveAll(products: List<IndexedProduct>) {
        if (products.isEmpty()) return
        breaker.executeRunnable { operations.save(products.map { it.toDocument() }) }
    }

    override fun deleteAllById(productIds: List<Long>) {
        breaker.executeRunnable {
            productIds.forEach { operations.delete(it.toString(), ProductDocument::class.java) }
        }
    }

    private fun IndexedProduct.toDocument(): ProductDocument =
        ProductDocument(
            id = id.toString(),
            name = name,
            price = price,
            createdAt = createdAt,
        )
}
