package demo.search.indexer.application.service

import demo.search.events.BoardChangeType
import demo.search.events.BoardChangedEvent
import demo.search.indexer.application.port.`in`.ApplyBoardChangeUseCase
import demo.search.indexer.application.port.out.BoardIndexPort
import demo.search.indexer.application.port.out.IndexerObservabilityPort
import demo.search.indexer.domain.IndexedBoard
import org.springframework.stereotype.Service

// 게시글 변경 이벤트 배치를 검색 인덱스에 반영한다.
//
// CREATED/UPDATED는 upsert(같은 id 문서 덮어쓰기), DELETED는 삭제로 매핑한다. 둘 다 id 기준 멱등 연산이라,
// 같은 이벤트가 재전달돼도(at-least-once) 결과가 같다.
//
// 배치 안에서 같은 게시글의 이벤트가 여러 건 올 수 있는데(같은 boardId → 같은 파티션이라 도착 순서 = 발생 순서),
// 이때는 "마지막 이벤트만" 반영하면 충분하다("last write wins"). 그 결과 상태가 CREATED/UPDATED면 upsert 대상,
// DELETED면 삭제 대상이며 둘은 서로 다른 boardId 집합이라, 저장/삭제 벌크 호출의 선후는 결과에 영향이 없다.
@Service
class BoardIndexService(
    private val boardIndexPort: BoardIndexPort,
    private val observability: IndexerObservabilityPort,
) : ApplyBoardChangeUseCase {
    override fun applyAll(events: List<BoardChangedEvent>) {
        if (events.isEmpty()) return

        // 같은 boardId는 마지막 이벤트만 남긴다(삽입 순서 유지 → 파티션 도착 순서 = 발생 순서).
        val lastPerBoard = LinkedHashMap<Long, BoardChangedEvent>()
        events.forEach { lastPerBoard[it.boardId] = it }

        val toSave = ArrayList<IndexedBoard>()
        val toDelete = ArrayList<Long>()
        lastPerBoard.values.forEach { event ->
            when (event.type) {
                BoardChangeType.CREATED, BoardChangeType.UPDATED -> toSave.add(event.toIndexedBoard())
                BoardChangeType.DELETED -> toDelete.add(event.boardId)
            }
        }

        // ES 벌크 쓰기 소요 시간을 타이머로 감싼다(색인 병목 관측). 카운터는 쓰기가 성공적으로 끝난 뒤에만 증가시켜,
        // ES 실패로 배치가 재시도될 때 중복 집계되지 않게 한다(saveAll/deleteAll은 멱등이라 재시도는 안전).
        observability.recordIndexingBatch {
            if (toSave.isNotEmpty()) boardIndexPort.saveAll(toSave)
            if (toDelete.isNotEmpty()) boardIndexPort.deleteAllById(toDelete)
        }
        observability.boardsIndexed(toSave.size)
        observability.boardsDeleted(toDelete.size)
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
