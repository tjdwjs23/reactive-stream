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

// 기동 시 검색 인덱스를 alias 기반으로 멱등 생성합니다: 버전 인덱스 'boards_v1'을 Nori 설정/매핑(@Setting/@Mapping)과
// 함께 만들고, alias 'boards'(= BoardDocument @Document indexName)를 그 위에 붙입니다(쓰기 인덱스로 지정).
// 이렇게 두면 이후 매핑을 바꿔도 재색인(reindexAll)이 새 버전 인덱스에 재구축 후 alias만 원자적으로 옮겨 무중단입니다.
// alias/인덱스가 이미 있으면 건너뜁니다(멱등). ES가 안 떠 있어도 기동을 막지 않도록 실패는 로그만 남깁니다(베스트에포트).
@Component
class BoardSearchIndexInitializer(
    private val operations: ElasticsearchOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexIfAbsent() {
        try {
            // BoardDocument의 @Document(indexName="boards")는 이제 '인덱스'가 아니라 'alias'를 가리킵니다.
            // exists()는 alias도 인식하므로, alias(또는 레거시 동일명 인덱스)가 있으면 그대로 둡니다.
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
                    "search will be unavailable until it exists. cause={}",
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
