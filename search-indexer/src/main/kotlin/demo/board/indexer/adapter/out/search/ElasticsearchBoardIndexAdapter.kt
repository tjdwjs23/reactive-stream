package demo.board.indexer.adapter.out.search

import demo.board.indexer.application.port.out.BoardIndexPort
import demo.board.indexer.domain.IndexedBoard
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Component

// BoardIndexPort의 Elasticsearch 구현(imperative). 컨슈머 스레드가 블로킹이므로 리액티브 대신 동기 Operations를 씁니다.
// saveAll은 _id(=게시글 id) 기준 upsert라 CREATED/UPDATED 모두 같은 문서를 덮어쓰고, 삭제는 없으면 무시됩니다(멱등).
// 도메인 IndexedBoard ↔ ES BoardDocument 변환은 이 어댑터 안에서만 일어납니다.
@Component
class ElasticsearchBoardIndexAdapter(
    private val operations: ElasticsearchOperations,
) : BoardIndexPort {
    // 벌크 upsert: save(Iterable)는 한 번의 ES bulk 요청으로 전 문서를 색인해 건별 왕복을 없앱니다(처리량 핵심 경로).
    override fun saveAll(boards: List<IndexedBoard>) {
        if (boards.isEmpty()) return
        operations.save(boards.map { it.toDocument() })
    }

    // 삭제(DELETED)는 upsert보다 훨씬 드물어 id별로 제거합니다. 없는 id는 예외 없이 무시됩니다(멱등).
    override fun deleteAllById(boardIds: List<Long>) {
        boardIds.forEach { operations.delete(it.toString(), BoardDocument::class.java) }
    }

    private fun IndexedBoard.toDocument(): BoardDocument =
        BoardDocument(
            id = id.toString(),
            title = title,
            content = content,
            createdAt = createdAt,
            viewCount = viewCount,
            authorId = authorId,
        )
}
