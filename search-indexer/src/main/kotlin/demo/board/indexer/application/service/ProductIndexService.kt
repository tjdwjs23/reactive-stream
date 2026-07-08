package demo.board.indexer.application.service

import demo.board.events.ProductChangeType
import demo.board.events.ProductChangedEvent
import demo.board.indexer.application.port.`in`.ApplyProductChangeUseCase
import demo.board.indexer.application.port.out.ProductIndexPort
import demo.board.indexer.application.port.out.ProductIndexerObservabilityPort
import demo.board.indexer.domain.IndexedProduct
import org.springframework.stereotype.Service

// 상품 변경 이벤트 배치를 products 인덱스에 반영합니다(BoardIndexService와 동일 로직).
// CREATED/UPDATED → upsert, DELETED → 삭제. id 기준 멱등. 같은 productId는 마지막 이벤트만 반영(last-write-wins).
@Service
class ProductIndexService(
    private val productIndexPort: ProductIndexPort,
    private val observability: ProductIndexerObservabilityPort,
) : ApplyProductChangeUseCase {
    override fun applyAll(events: List<ProductChangedEvent>) {
        if (events.isEmpty()) return

        val lastPerProduct = LinkedHashMap<Long, ProductChangedEvent>()
        events.forEach { lastPerProduct[it.productId] = it }

        val toSave = ArrayList<IndexedProduct>()
        val toDelete = ArrayList<Long>()
        lastPerProduct.values.forEach { event ->
            when (event.type) {
                ProductChangeType.CREATED, ProductChangeType.UPDATED -> toSave.add(event.toIndexedProduct())
                ProductChangeType.DELETED -> toDelete.add(event.productId)
            }
        }

        observability.recordIndexingBatch {
            if (toSave.isNotEmpty()) productIndexPort.saveAll(toSave)
            if (toDelete.isNotEmpty()) productIndexPort.deleteAllById(toDelete)
        }
        observability.productsIndexed(toSave.size)
        observability.productsDeleted(toDelete.size)
    }

    private fun ProductChangedEvent.toIndexedProduct(): IndexedProduct =
        IndexedProduct(
            id = productId,
            name = requireNotNull(name) { "CREATED/UPDATED 이벤트에는 name이 있어야 합니다 (productId=$productId)" },
            price = requireNotNull(price) { "CREATED/UPDATED 이벤트에는 price가 있어야 합니다 (productId=$productId)" },
            createdAt = requireNotNull(createdAt) { "CREATED/UPDATED 이벤트에는 createdAt이 있어야 합니다 (productId=$productId)" },
        )
}
