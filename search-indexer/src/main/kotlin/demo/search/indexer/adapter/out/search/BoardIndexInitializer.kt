package demo.search.indexer.adapter.out.search

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.elasticsearch.core.index.AliasAction
import org.springframework.data.elasticsearch.core.index.AliasActionParameters
import org.springframework.data.elasticsearch.core.index.AliasActions
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import org.springframework.stereotype.Component

// 기동 시 검색 인덱스를 alias 기반으로 멱등 생성합니다: 버전 인덱스 'boards_v1'을 Nori 설정/매핑(@Setting/@Mapping)과
// 함께 만들고, alias 'boards'를 그 위에 붙입니다(쓰기 인덱스로 지정). search-indexer가 이 인덱스의 writer이므로
// 스키마 생성 책임도 이쪽에 둡니다(search-service는 reader). 이벤트 색인은 alias로 향하므로 재색인 스왑과 무관하게 동작합니다.
// alias/인덱스가 이미 있으면 건너뜁니다(멱등). ES가 안 떠 있어도 기동을 막지 않도록 실패는 로그만 남깁니다(베스트에포트).
@Component
class BoardIndexInitializer(
    private val operations: ElasticsearchOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexIfAbsent() {
        try {
            val aliasOps = operations.indexOps(BoardDocument::class.java)
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
            log.info(
                "created '{}' with Nori settings/mappings and alias '{}' -> '{}'",
                INITIAL_VERSION,
                ALIAS,
                INITIAL_VERSION,
            )
        } catch (e: Exception) {
            log.warn(
                "failed to initialize '{}' index/alias (is Elasticsearch up?); " +
                    "indexing will fail until it exists. cause={}",
                ALIAS,
                e.toString(),
            )
        }
    }

    private companion object {
        const val ALIAS = "boards"
        const val INITIAL_VERSION = "boards_v1"
    }
}
