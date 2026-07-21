package demo.search.adapter.out.search

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import demo.search.application.port.out.ProductSearchHit
import demo.search.application.port.out.ProductSearchPort
import demo.search.config.ResilienceConfig
import demo.search.domain.model.Product
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import org.springframework.data.elasticsearch.core.query.HighlightQuery
import org.springframework.data.elasticsearch.core.query.highlight.Highlight
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters
import org.springframework.stereotype.Component

// ProductSearchPort의 Elasticsearch 구현. BoardSearchAdapter와 동일한 alias 무중단 재색인 전략(products alias +
// 버전 인덱스)을 쓰고, 추가로 초성/접두 자동완성 쿼리를 제공합니다.
// - 일반 검색: name(Nori) + name.ngram(접두 edge_ngram)로 multi_match, _score 내림차순, <em> 하이라이트.
// - 자동완성: 질의를 name.jamo(기본)와 name.chosung(순수 초성 질의)로 라우팅합니다 — 순수 초성("ㅅㄱ")은
//   name.chosung(초성+edge_ngram)에도 매칭하고, 완성형 음절이 섞이면("사ㄱ"·"삼") name.jamo(전체 자모 edge_ngram)로만
//   매칭해 완성형 음절이 초성으로 뭉개지지 않게 합니다.
@Component
class ProductSearchAdapter(
    private val operations: ElasticsearchOperations,
    private val client: ElasticsearchClient,
    private val productDocumentMapper: ProductDocumentMapper,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : ProductSearchPort {
    private val log = LoggerFactory.getLogger(javaClass)

    // 공개 read path(검색/자동완성)에 서킷브레이커. ES가 반복 실패/지연하면 즉시 실패시켜 요청이 매달리지 않게 합니다.
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ELASTICSEARCH_SEARCH)

    override fun search(
        keyword: String,
        size: Int,
    ): List<ProductSearchHit> {
        val query =
            Query.of { q ->
                q.multiMatch { m -> m.query(keyword).fields("name^2", "name.ngram") }
            }
        return execute(query, size)
    }

    override fun autocomplete(
        prefix: String,
        size: Int,
    ): List<ProductSearchHit> {
        // 완성형 음절과 초성을 구분하기 위해 두 가지 매칭을 라우팅합니다:
        // - 질의가 "순수 초성"(모두 호환 자음 자모, 예 "ㅅㄱ")이면 name.chosung(초성만 edge_ngram)에 매칭 →
        //   초성이 ㅅㄱ로 시작하는 모든 상품(사과·삼계탕·삼각김밥).
        // - 그 외(완성형 음절이 섞이면, 예 "사ㄱ"·"삼")는 name.jamo(전체 자모 edge_ngram)에 매칭 →
        //   "사ㄱ"=ㅅㅏㄱ은 사과(ㅅㅏㄱ…)만, "삼"=ㅅㅏㅁ은 삼계탕·삼각김밥만. 완성형 음절이 초성으로 뭉개지지 않습니다.
        // name.jamo는 순수 초성 질의("ㅅㄱ"=ㅅㄱ)를 매칭하지 못하므로(문서 자모엔 중성이 끼어 있음), 초성 질의는 chosung로만 처리합니다.
        val isChosungOnly = prefix.isNotEmpty() && prefix.all { it in 'ㄱ'..'ㅎ' }
        val query =
            Query.of { q ->
                q.bool { b ->
                    b.should { s -> s.match { m -> m.field("name.jamo").query(prefix) } }
                    if (isChosungOnly) {
                        b.should { s -> s.match { m -> m.field("name.chosung").query(prefix) } }
                    }
                    b.minimumShouldMatch("1")
                }
            }
        return execute(query, size)
    }

    private fun execute(
        query: Query,
        size: Int,
    ): List<ProductSearchHit> {
        val highlight =
            Highlight(
                HighlightParameters
                    .builder()
                    .withPreTags("<em>")
                    .withPostTags("</em>")
                    .withNumberOfFragments(0)
                    .build(),
                listOf(HighlightField("name")),
            )
        val nativeQuery =
            NativeQuery
                .builder()
                .withQuery(query)
                .withPageable(PageRequest.of(0, size))
                .withHighlightQuery(HighlightQuery(highlight, ProductDocument::class.java))
                .build()
        val hits = breaker.executeSupplier { operations.search(nativeQuery, ProductDocument::class.java).searchHits }
        return hits.map { hit ->
            ProductSearchHit(
                product = productDocumentMapper.toDomain(hit.content),
                score = hit.score.toDouble(),
                highlightedName = hit.getHighlightField("name").firstOrNull(),
            )
        }
    }

    override fun createNewVersionIndex(): String {
        val entityOps = operations.indexOps(ProductDocument::class.java)
        val settings = entityOps.createSettings()
        val mapping = entityOps.createMapping()
        val name = VERSION_PREFIX + System.currentTimeMillis()
        operations.indexOps(IndexCoordinates.of(name)).create(settings, mapping)
        log.info("created new product version index '{}'", name)
        return name
    }

    override fun indexInto(
        products: List<Product>,
        indexName: String,
    ): Int {
        if (products.isEmpty()) return 0
        val documents = products.map { productDocumentMapper.toDocument(it) }
        operations.save(documents, IndexCoordinates.of(indexName))
        return documents.size
    }

    override fun promote(indexName: String) {
        val current = currentAliasedIndices()
        client.indices().updateAliases { u ->
            current
                .filter { it != indexName }
                .forEach { old -> u.actions { a -> a.remove { r -> r.index(old).alias(ALIAS) } } }
            u.actions { a -> a.add { add -> add.index(indexName).alias(ALIAS).isWriteIndex(true) } }
            u
        }
        log.info("promoted alias '{}' -> '{}' (was {})", ALIAS, indexName, current)
        current
            .filter { it != indexName }
            .forEach { old ->
                try {
                    client.indices().delete { it.index(old) }
                } catch (e: Exception) {
                    log.warn("failed to delete old product version index '{}': {}", old, e.toString())
                }
            }
    }

    override fun deleteVersionIndex(indexName: String) {
        try {
            operations.indexOps(IndexCoordinates.of(indexName)).delete()
            log.info("deleted (discarded) product version index '{}'", indexName)
        } catch (e: Exception) {
            log.warn("failed to delete product version index '{}': {}", indexName, e.toString())
        }
    }

    private fun currentAliasedIndices(): Set<String> =
        try {
            client
                .indices()
                .getAlias { it.name(ALIAS) }
                .aliases()
                .keys
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }

    private companion object {
        const val ALIAS = "products"
        const val VERSION_PREFIX = "products_"
    }
}
