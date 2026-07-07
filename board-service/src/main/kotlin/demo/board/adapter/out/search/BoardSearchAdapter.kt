package demo.board.adapter.out.search

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
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
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
    // 요청이 ES 타임아웃만큼 매달리지 않습니다. 색인(index/indexAll/deleteById)은 관리 재색인 경로라 대상에서 제외합니다.
    private val breaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ELASTICSEARCH_SEARCH)

    // upsert: 같은 _id(=게시글 id) 문서가 있으면 통째로 덮어씁니다.
    override suspend fun index(board: Board) {
        operations.save(boardDocumentMapper.toDocument(board)).awaitSingle()
    }

    // 벌크 upsert: saveAll로 한 번에 색인해 건별 왕복을 줄입니다. 반환값은 색인된 문서 수.
    override suspend fun indexAll(boards: List<Board>): Int {
        if (boards.isEmpty()) return 0
        val documents = boards.map { boardDocumentMapper.toDocument(it) }
        return operations
            .saveAll(documents, BoardDocument::class.java)
            .asFlow()
            .count()
    }

    // 색인에서 제거. 문서가 없어도 예외 없이 조용히 완료됩니다.
    override suspend fun deleteById(id: Long) {
        operations.delete(id.toString(), BoardDocument::class.java).awaitFirstOrNull()
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
}
