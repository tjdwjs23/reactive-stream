package demo.board.indexer.adapter.out.search

import demo.board.indexer.application.port.out.BoardIndexPort
import demo.board.indexer.domain.IndexedBoard
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.stereotype.Component

// BoardIndexPort의 Elasticsearch 구현(imperative). 컨슈머 스레드가 블로킹이므로 리액티브 대신 동기 Operations를 씁니다.
// save는 _id(=게시글 id) 기준 upsert라 CREATED/UPDATED 모두 같은 문서를 덮어쓰고, delete는 없으면 무시됩니다(멱등).
// 도메인 IndexedBoard ↔ ES BoardDocument 변환은 이 어댑터 안에서만 일어납니다.
@Component
class ElasticsearchBoardIndexAdapter(
    private val operations: ElasticsearchOperations,
) : BoardIndexPort {
    override fun save(board: IndexedBoard) {
        operations.save(board.toDocument())
    }

    override fun deleteById(boardId: Long) {
        operations.delete(boardId.toString(), BoardDocument::class.java)
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
