package demo.board.adapter.out.search

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import demo.board.application.port.out.BoardSearchHit
import demo.board.application.port.out.BoardSearchPort
import demo.board.config.ResilienceConfig
import demo.board.domain.model.Board
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.data.domain.PageRequest
import org.springframework.data.elasticsearch.client.elc.NativeQuery
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchOperations
import org.springframework.data.elasticsearch.core.query.HighlightQuery
import org.springframework.data.elasticsearch.core.query.highlight.Highlight
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters
import org.springframework.stereotype.Component

// BoardSearchPort의 Elasticsearch 구현체. ReactiveElasticsearchOperations로 논블로킹 색인/검색을 합니다.
// 서비스는 이 클래스를 모르고 BoardSearchPort 인터페이스에만 의존합니다(포트-어댑터 경계).
@Component
class BoardSearchAdapter(
    private val operations: ReactiveElasticsearchOperations,
    private val boardDocumentMapper: BoardDocumentMapper,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : BoardSearchPort {
    // 검색(공개 read path)에 서킷브레이커를 겁니다. ES가 반복 실패/지연하면 서킷이 열려 검색이 즉시 실패하고,
    // 요청이 ES 타임아웃만큼 매달리지 않습니다. 벌크 색인(indexAll)은 관리 재색인 경로라 대상에서 제외합니다.
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ELASTICSEARCH_SEARCH)

    // 벌크 upsert: saveAll로 한 번에 색인해 건별 왕복을 줄입니다. 반환값은 색인된 문서 수.
    override suspend fun indexAll(boards: List<Board>): Int {
        if (boards.isEmpty()) return 0
        val documents = boards.map { boardDocumentMapper.toDocument(it) }
        return operations
            .saveAll(documents, BoardDocument::class.java)
            .asFlow()
            .count()
    }

    // 고아 문서 정리: 전체 문서를 createdAt 오름차순 + search_after로 순회하며, 정본(DB)에 없는 문서(id ∉ keepIds)를
    // 삭제합니다. _id 필드는 기본적으로 fielddata 정렬이 금지돼 정렬 키로 쓸 수 없으므로, 모든 문서에 항상 존재하는
    // createdAt(date, doc_values)로 정렬합니다. createdAt은 유일하지 않아 이론상 동일 시각이 페이지 경계에 걸치면
    // 소수가 누락될 수 있으나(다음 재색인이 수거), 대량에서도 일정 메모리로 도는 안전한(끝에서 정리) 방식입니다.
    // 재색인 도중 생성돼 아직 keepIds에 없는 문서를 지우지 않도록 max(keepIds) 이하만 삭제합니다.
    override suspend fun pruneExcept(keepIds: Set<Long>): Int {
        val maxKeep = keepIds.maxOrNull()
        var searchAfter: List<Any>? = null
        var pruned = 0
        while (true) {
            val builder =
                NativeQuery
                    .builder()
                    .withQuery(Query.of { q -> q.matchAll { it } })
                    .withSort { s -> s.field { f -> f.field("createdAt").order(SortOrder.Asc) } }
                    .withPageable(PageRequest.of(0, PRUNE_PAGE_SIZE))
            searchAfter?.let { builder.withSearchAfter(it) }

            val hits =
                operations
                    .search(builder.build(), BoardDocument::class.java)
                    .asFlow()
                    .toList()
            if (hits.isEmpty()) break

            val orphanIds =
                hits
                    .map { it.content.id.toLong() }
                    .filter { it !in keepIds && (maxKeep == null || it <= maxKeep) }
            orphanIds.forEach { operations.delete(it.toString(), BoardDocument::class.java).awaitFirstOrNull() }
            pruned += orphanIds.size

            searchAfter = hits.last().sortValues
            if (hits.size < PRUNE_PAGE_SIZE) break
        }
        return pruned
    }

    // 키워드 전문검색: title/content를 대상으로 multi_match. title에 가중치(^2)를 줘 제목 매칭을 더 높게 칩니다.
    // Nori 분석기는 색인/검색 양쪽에 매핑으로 걸려 있어, 한글 형태소 단위로 매칭되고 _score(관련도)로 정렬됩니다.
    override fun search(
        keyword: String,
        size: Int,
    ): Flow<BoardSearchHit> {
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

        return operations
            .search(nativeQuery, BoardDocument::class.java)
            // 서킷이 열려 있으면 여기서 CallNotPermittedException으로 즉시 실패합니다(ES 왕복 없이).
            .transformDeferred(CircuitBreakerOperator.of(breaker))
            .asFlow()
            .map { hit ->
                BoardSearchHit(
                    board = boardDocumentMapper.toDomain(hit.content),
                    score = hit.score.toDouble(),
                    highlightedTitle = hit.getHighlightField("title").firstOrNull(),
                    highlightedContent = hit.getHighlightField("content").firstOrNull(),
                )
            }
    }

    private companion object {
        // 고아 정리 순회의 페이지 크기(search_after 한 번에 훑는 문서 수).
        const val PRUNE_PAGE_SIZE = 1000
    }
}
