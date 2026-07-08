package demo.board.application.service

import demo.board.application.port.`in`.AutocompleteProductUseCase
import demo.board.application.port.`in`.ProductAutocompleteQuery
import demo.board.application.port.`in`.ProductReindexResult
import demo.board.application.port.`in`.ProductSearchQuery
import demo.board.application.port.`in`.ReindexProductsUseCase
import demo.board.application.port.`in`.SearchProductUseCase
import demo.board.application.port.out.ProductObservabilityPort
import demo.board.application.port.out.ProductRepositoryPort
import demo.board.application.port.out.ProductSearchHit
import demo.board.application.port.out.ProductSearchPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 상품 검색/자동완성(reader)과 전체 재색인 서비스. BoardSearchService와 동일한 철학:
// 검색은 ES만 읽어 트랜잭션이 필요 없고, 재색인은 새 버전 인덱스 재구축 후 alias 스왑(무중단, 실패 시 폐기·롤백)입니다.
@Service
class ProductSearchService(
    private val productSearchPort: ProductSearchPort,
    private val productRepositoryPort: ProductRepositoryPort,
    private val observability: ProductObservabilityPort,
) : SearchProductUseCase,
    AutocompleteProductUseCase,
    ReindexProductsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun search(query: ProductSearchQuery): List<ProductSearchHit> =
        productSearchPort
            .search(query.keyword, query.size)
            .also { observability.productSearched(it.size) }

    override fun autocomplete(query: ProductAutocompleteQuery): List<ProductSearchHit> =
        productSearchPort
            .autocomplete(query.prefix, query.size)
            .also { observability.productAutocompleted(it.size) }

    override fun reindexAll(): ProductReindexResult {
        val newIndex = productSearchPort.createNewVersionIndex()
        var cursor: Long? = null
        var indexed = 0L
        var failed = 0L
        try {
            while (true) {
                val page = productRepositoryPort.findPage(cursor, REINDEX_PAGE_SIZE)
                if (page.isEmpty()) break
                try {
                    indexed += productSearchPort.indexInto(page, newIndex)
                } catch (e: Exception) {
                    failed += page.size
                    log.error(
                        "reindex page failed (cursor={}, size={}); skip. cause={}",
                        cursor,
                        page.size,
                        e.toString(),
                    )
                }
                cursor = page.last().id
                if (page.size < REINDEX_PAGE_SIZE) break
            }
        } catch (e: Exception) {
            productSearchPort.deleteVersionIndex(newIndex)
            throw e
        }

        val swapped =
            if (failed == 0L) {
                productSearchPort.promote(newIndex)
                true
            } else {
                log.error("product reindex had {} failed docs; alias NOT swapped, discarding {}", failed, newIndex)
                productSearchPort.deleteVersionIndex(newIndex)
                false
            }

        log.info(
            "product reindexAll completed: indexed={}, failed={}, swapped={}, index={}",
            indexed,
            failed,
            swapped,
            newIndex,
        )
        return ProductReindexResult(indexed = indexed, failed = failed, swapped = swapped)
    }

    companion object {
        private const val REINDEX_PAGE_SIZE = 500
    }
}
