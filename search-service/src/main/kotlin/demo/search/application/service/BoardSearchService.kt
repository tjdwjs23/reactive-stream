package demo.search.application.service

import demo.search.application.port.`in`.BoardSearchQuery
import demo.search.application.port.`in`.ReindexBoardsUseCase
import demo.search.application.port.`in`.ReindexResult
import demo.search.application.port.`in`.SearchBoardUseCase
import demo.search.application.port.out.BoardRepositoryPort
import demo.search.application.port.out.BoardSearchHit
import demo.search.application.port.out.BoardSearchPort
import demo.search.application.port.out.ObservabilityPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 검색(읽기)과 전체 재색인을 담당하는 reader 서비스. 쓰기 경로의 색인은 여기서 하지 않습니다
// (BoardService가 아웃박스 이벤트를 남기면 search-indexer가 Kafka로 소비해 ES에 반영합니다).
// 검색은 ES만 읽으므로 DB 트랜잭션(@Transactional)이 필요 없습니다. MVC 스택이라 모두 블로킹 함수입니다.
@Service
class BoardSearchService(
    private val boardSearchPort: BoardSearchPort,
    private val boardRepositoryPort: BoardRepositoryPort,
    private val observability: ObservabilityPort,
) : SearchBoardUseCase,
    ReindexBoardsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    // 관련도순으로 상위 size건을 조회합니다.
    override fun search(query: BoardSearchQuery): List<BoardSearchHit> =
        boardSearchPort
            .search(query.keyword, query.size)
            .also { observability.boardSearched(it.size) }

    // DB(정본)를 새 버전 인덱스에 재구축한 뒤 alias('boards')를 원자적으로 스왑합니다(무중단 재색인).
    // (1) 새 버전 인덱스 생성 → (2) DB를 키셋 페이지네이션으로 순회하며 그 인덱스에 벌크 색인
    //   (한 번에 REINDEX_PAGE_SIZE건만 메모리에 올려 데이터가 커져도 일정 메모리) → (3) 전량 성공 시 alias 스왑.
    // 한 페이지라도 실패하면(failed>0) 새 인덱스가 불완전하므로 스왑하지 않고 폐기합니다 — 검색은 기존 인덱스를
    // 그대로 보며 무중단·자동 롤백됩니다. 새 인덱스로 깨끗이 재구축하므로 과거의 고아(orphan) 정리가 필요 없습니다.
    override fun reindexAll(): ReindexResult {
        val newIndex = boardSearchPort.createNewVersionIndex()
        var cursor: Long? = null
        var indexed = 0L
        var failed = 0L
        try {
            while (true) {
                val page = boardRepositoryPort.findPage(cursor, REINDEX_PAGE_SIZE)
                if (page.isEmpty()) break
                try {
                    indexed += boardSearchPort.indexInto(page, newIndex)
                } catch (e: Exception) {
                    failed += page.size
                    log.error(
                        "reindex page failed (cursor={}, size={}); skip page. cause={}",
                        cursor,
                        page.size,
                        e.toString(),
                    )
                }
                cursor = page.last().id
                if (page.size < REINDEX_PAGE_SIZE) break
            }
        } catch (e: Exception) {
            // 순회 자체가 깨진 경우(예: DB 장애): 반쯤 만든 인덱스를 폐기하고 실패를 전파합니다.
            boardSearchPort.deleteVersionIndex(newIndex)
            throw e
        }

        val swapped =
            if (failed == 0L) {
                boardSearchPort.promote(newIndex)
                true
            } else {
                // 불완전한 인덱스로 스왑하면 검색 품질이 오히려 나빠지므로, alias를 옮기지 않고 폐기합니다(자동 롤백).
                log.error("reindex had {} failed docs; alias NOT swapped, discarding {}", failed, newIndex)
                boardSearchPort.deleteVersionIndex(newIndex)
                false
            }

        log.info(
            "reindexAll completed: indexed={}, failed={}, swapped={}, index={}",
            indexed,
            failed,
            swapped,
            newIndex,
        )
        return ReindexResult(indexed = indexed, failed = failed, swapped = swapped)
    }

    companion object {
        private const val REINDEX_PAGE_SIZE = 500
    }
}
