package demo.board.adapter.out.search

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import demo.board.application.port.out.BoardSearchHit
import demo.board.application.port.out.BoardSearchPort
import demo.board.config.ResilienceConfig
import demo.board.domain.model.Board
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

// BoardSearchPort의 Elasticsearch 구현체. MVC 스택이라 블로킹 ElasticsearchOperations로 검색/색인을 합니다.
// 서비스는 이 클래스를 모르고 BoardSearchPort 인터페이스에만 의존합니다(포트-어댑터 경계).
//
// Alias 전략: 검색/이벤트색인은 항상 alias 'boards'로 접근하고, 실제 데이터는 뒤의 버전 인덱스('boards_v1',
// 재색인 시 'boards_<ts>')에 있습니다. 무중단 재색인은 새 버전 인덱스에 전량 재구축 후 alias를 원자적으로
// 옮기는 방식입니다(스왑 전까지 검색은 옛 인덱스를 봄 → 무중단, 스왑 실패/미실행 시 자동 롤백).
// alias 이동/버전 인덱스 삭제 같은 관리 작업은 저수준 ElasticsearchClient로, 문서 색인/검색은 Operations로 합니다.
@Component
class BoardSearchAdapter(
    private val operations: ElasticsearchOperations,
    private val client: ElasticsearchClient,
    private val boardDocumentMapper: BoardDocumentMapper,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : BoardSearchPort {
    private val log = LoggerFactory.getLogger(javaClass)

    // 검색(공개 read path)에 서킷브레이커를 겁니다. ES가 반복 실패/지연하면 서킷이 열려 검색이 즉시 실패하고
    // (CallNotPermittedException), 요청이 ES 타임아웃만큼 매달리지 않습니다. 재색인(관리 경로)은 대상에서 제외합니다.
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ELASTICSEARCH_SEARCH)

    // 새 버전 인덱스를 현재 매핑/설정(@Setting/@Mapping)으로 만들고 이름을 반환합니다(alias는 아직 미이동).
    // 이름은 'boards_<epochMillis>'로 매번 유일합니다. 설정/매핑은 엔티티(BoardDocument)에서 가져와 재사용합니다.
    override fun createNewVersionIndex(): String {
        val entityOps = operations.indexOps(BoardDocument::class.java)
        val settings = entityOps.createSettings()
        val mapping = entityOps.createMapping()
        val name = VERSION_PREFIX + System.currentTimeMillis()
        operations.indexOps(IndexCoordinates.of(name)).create(settings, mapping)
        log.info("created new version index '{}'", name)
        return name
    }

    // 지정 버전 인덱스에 벌크 색인(upsert). save(Iterable, IndexCoordinates)로 한 번에 색인해 건별 왕복을 줄입니다.
    override fun indexInto(
        boards: List<Board>,
        indexName: String,
    ): Int {
        if (boards.isEmpty()) return 0
        val documents = boards.map { boardDocumentMapper.toDocument(it) }
        operations.save(documents, IndexCoordinates.of(indexName))
        return documents.size
    }

    // alias 'boards'를 지정 버전 인덱스로 원자적으로 이동(쓰기 인덱스로 지정)하고, alias가 가리키던 옛 버전은 삭제합니다.
    // updateAliases는 remove+add를 한 요청으로 원자 적용하므로, 검색이 "인덱스 없음"을 보는 순간이 없습니다(무중단).
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
                    log.warn("failed to delete old version index '{}': {}", old, e.toString())
                }
            }
    }

    // 반쯤 만들다 실패한 버전 인덱스를 삭제합니다(스왑하지 않고 롤백).
    override fun deleteVersionIndex(indexName: String) {
        try {
            operations.indexOps(IndexCoordinates.of(indexName)).delete()
            log.info("deleted (discarded) version index '{}'", indexName)
        } catch (e: Exception) {
            log.warn("failed to delete version index '{}': {}", indexName, e.toString())
        }
    }

    // alias 'boards'가 현재 가리키는 인덱스 이름들. alias가 아직 없으면 빈 집합.
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

    // 키워드 전문검색: alias('boards')를 통해 title/content를 multi_match. title에 가중치(^2)를 줘 제목 매칭을 더 높게 칩니다.
    // Nori 분석기는 색인/검색 양쪽에 매핑으로 걸려 있어, 한글 형태소 단위로 매칭되고 _score(관련도)로 정렬됩니다.
    override fun search(
        keyword: String,
        size: Int,
    ): List<BoardSearchHit> {
        val query =
            Query.of { q ->
                q.multiMatch { m ->
                    m.query(keyword).fields("title^2", "content")
                }
            }

        // 매칭된 부분을 <em>...</em>로 감싸 하이라이트합니다. number_of_fragments(0)=필드 전체 반환.
        val highlight =
            Highlight(
                HighlightParameters
                    .builder()
                    .withPreTags("<em>")
                    .withPostTags("</em>")
                    .withNumberOfFragments(0)
                    .build(),
                listOf(HighlightField("title"), HighlightField("content")),
            )

        val nativeQuery =
            NativeQuery
                .builder()
                .withQuery(query)
                // 관련도(_score) 내림차순이 기본 정렬이므로 별도 sort 없이 상위 size건만 가져옵니다.
                .withPageable(PageRequest.of(0, size))
                .withHighlightQuery(HighlightQuery(highlight, BoardDocument::class.java))
                .build()

        // 서킷이 열려 있으면 여기서 CallNotPermittedException으로 즉시 실패합니다(ES 왕복 없이).
        val hits = breaker.executeSupplier { operations.search(nativeQuery, BoardDocument::class.java).searchHits }
        return hits.map { hit ->
            BoardSearchHit(
                board = boardDocumentMapper.toDomain(hit.content),
                score = hit.score.toDouble(),
                highlightedTitle = hit.getHighlightField("title").firstOrNull(),
                highlightedContent = hit.getHighlightField("content").firstOrNull(),
            )
        }
    }

    private companion object {
        // 읽기/쓰기가 향하는 alias 이름(= BoardDocument @Document indexName). 실제 데이터는 뒤의 버전 인덱스에.
        const val ALIAS = "boards"

        // 버전 인덱스 접두사. 'boards_v1'(최초) / 'boards_<ts>'(재색인)가 이 접두사를 공유합니다.
        const val VERSION_PREFIX = "boards_"
    }
}
