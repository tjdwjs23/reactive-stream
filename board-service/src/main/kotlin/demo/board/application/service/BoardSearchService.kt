package demo.board.application.service

import demo.board.application.port.`in`.BoardSearchQuery
import demo.board.application.port.`in`.ReindexBoardsUseCase
import demo.board.application.port.`in`.ReindexResult
import demo.board.application.port.`in`.SearchBoardUseCase
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardSearchHit
import demo.board.application.port.out.BoardSearchPort
import demo.board.application.port.out.ObservabilityPort
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

    // DB(정본)를 키셋 페이지네이션으로 순회하며 ES에 다시 색인합니다.
    // 한 번에 REINDEX_PAGE_SIZE건만 메모리에 올리므로 데이터가 커져도 일정한 메모리로 동작합니다.
    // - 색인은 페이지 단위 벌크(indexAll)로 수행해 건별 왕복을 없앱니다.
    // - 한 페이지 색인이 실패해도 전체를 중단하지 않고 그 페이지만 건너뛰며 실패 건수를 집계합니다
    //   (플러시/아카이브 배치와 같은 내결함성 철학).
    override fun reindexAll(): ReindexResult {
        var cursor: Long? = null
        var indexed = 0L
        var failed = 0L
        // 정본(DB)에 존재하는 전체 게시글 id. 재색인 후 이 집합에 없는 ES 문서(고아)를 정리하는 데 씁니다.
        // 색인 실패 페이지의 id도 포함시켜(정본엔 존재하므로) 그 문서를 고아로 오인해 지우지 않게 합니다.
        val keepIds = HashSet<Long>()
        while (true) {
            val page = boardRepositoryPort.findPage(cursor, REINDEX_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { board -> board.id?.let(keepIds::add) }
            try {
                indexed += boardSearchPort.indexAll(page)
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

        // 색인(upsert) 후 고아 정리: 정본에 없는 문서를 제거해 "삭제됐는데 검색엔 남는" 불일치를 회복합니다.
        // upsert를 먼저 끝낸 뒤 정리하므로, 정리가 실패해도 색인 결과는 보존됩니다(안전한 끝단 정리).
        val pruned =
            try {
                boardSearchPort.pruneExcept(keepIds).toLong()
            } catch (e: Exception) {
                log.error("reindex prune(고아 정리) 실패; 색인 결과는 유지됩니다. cause={}", e.toString())
                0L
            }

        log.info("reindexAll completed: indexed={}, failed={}, pruned={}", indexed, failed, pruned)
        return ReindexResult(indexed = indexed, failed = failed, pruned = pruned)
    }

    companion object {
        private const val REINDEX_PAGE_SIZE = 500
    }
}
