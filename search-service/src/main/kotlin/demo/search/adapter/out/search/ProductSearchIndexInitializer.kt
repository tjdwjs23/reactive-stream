package demo.search.adapter.out.search

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.index.AliasAction
import org.springframework.data.elasticsearch.core.index.AliasActionParameters
import org.springframework.data.elasticsearch.core.index.AliasActions
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import org.springframework.stereotype.Component

// 기동 시 상품 검색 인덱스를 alias 기반으로 멱등 생성합니다(BoardSearchIndexInitializer와 동일 패턴):
// 버전 인덱스 'products_v1'을 Nori+ICU 설정/매핑과 함께 만들고 alias 'products'를 그 위에 붙입니다(쓰기 인덱스).
// ES가 안 떠 있어도 기동을 막지 않도록 실패는 로그만 남깁니다(베스트에포트).
@Component
class ProductSearchIndexInitializer(
    private val operations: ElasticsearchOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexIfAbsent() {
        try {
            val aliasOps = operations.indexOps(ProductDocument::class.java)
            if (aliasOps.exists()) {
                log.info("'{}' (alias or index) already exists — skip creation", ALIAS)
                return
            }
            val settings = aliasOps.createSettings()
            val mapping = aliasOps.createMapping()
            val versionOps = operations.indexOps(IndexCoordinates.of(INITIAL_VERSION))
            if (!versionOps.exists()) {
                versionOps.create(settings, mapping)
            }
            versionOps.alias(
                AliasActions().add(
                    AliasAction.Add(
                        AliasActionParameters
                            .builder()
                            .withIndices(INITIAL_VERSION)
                            .withAliases(ALIAS)
                            .withIsWriteIndex(true)
                            .build(),
                    ),
                ),
            )
            log.info("created '{}' with Nori+ICU settings/mappings and alias '{}'", INITIAL_VERSION, ALIAS)
        } catch (e: Exception) {
            log.warn("failed to initialize '{}' index/alias (is Elasticsearch up?). cause={}", ALIAS, e.toString())
        }
    }

    private companion object {
        const val ALIAS = "products"
        const val INITIAL_VERSION = "products_v1"
    }
}
