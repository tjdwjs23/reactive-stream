package demo.board.indexer.application.service

import demo.board.events.BoardChangeType
import demo.board.events.BoardChangedEvent
import demo.board.indexer.application.port.`in`.ApplyBoardChangeUseCase
import demo.board.indexer.application.port.out.BoardIndexPort
import demo.board.indexer.domain.IndexedBoard
import org.springframework.stereotype.Service

// 게시글 변경 이벤트를 검색 인덱스에 반영한다.
//
// CREATED/UPDATED는 upsert(같은 id 문서 덮어쓰기), DELETED는 삭제로 매핑한다. 둘 다 id 기준 멱등 연산이라,
// 같은 이벤트가 재전달돼도(at-least-once) 결과가 같다. 같은 게시글의 이벤트 순서는 Kafka 파티션(key=boardId)이
// 보장하므로, 별도 이벤트 순번 저장 없이 "마지막 이벤트가 이긴다"로 충분하다.
@Service
class BoardIndexService(
    private val boardIndexPort: BoardIndexPort,
) : ApplyBoardChangeUseCase {
    override fun apply(event: BoardChangedEvent) {
        when (event.type) {
            BoardChangeType.CREATED, BoardChangeType.UPDATED -> boardIndexPort.save(event.toIndexedBoard())
            BoardChangeType.DELETED -> boardIndexPort.deleteById(event.boardId)
        }
    }

    // CREATED/UPDATED 이벤트는 색인에 필요한 필드가 항상 채워져 있다(프로듀서 계약). 누락 시 계약 위반이므로 즉시 실패한다.
    private fun BoardChangedEvent.toIndexedBoard(): IndexedBoard =
        IndexedBoard(
            id = boardId,
            title = requireNotNull(title) { "CREATED/UPDATED 이벤트에는 title이 있어야 합니다 (boardId=$boardId)" },
            content = requireNotNull(content) { "CREATED/UPDATED 이벤트에는 content가 있어야 합니다 (boardId=$boardId)" },
            viewCount = viewCount,
            createdAt = requireNotNull(createdAt) { "CREATED/UPDATED 이벤트에는 createdAt이 있어야 합니다 (boardId=$boardId)" },
            authorId = authorId,
        )
}
