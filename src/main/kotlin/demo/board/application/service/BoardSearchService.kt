package demo.board.application.service

import demo.board.application.port.`in`.BoardSearchQuery
import demo.board.application.port.`in`.ReindexBoardsUseCase
import demo.board.application.port.`in`.ReindexResult
import demo.board.application.port.`in`.SearchBoardUseCase
import demo.board.application.port.out.BoardRepositoryPort
import demo.board.application.port.out.BoardSearchHit
import demo.board.application.port.out.BoardSearchPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 검색(읽기)과 전체 재색인을 담당하는 서비스. 쓰기 경로의 인라인 색인은 BoardService가 처리합니다.
// 검색은 ES만 읽으므로 DB 트랜잭션(@Transactional)이 필요 없습니다.
@Service
class BoardSearchService(
    private val boardSearchPort: BoardSearchPort,
    private val boardRepositoryPort: BoardRepositoryPort,
) : SearchBoardUseCase,
    ReindexBoardsUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    // 관련도순으로 흘러오는 Flow를 한 페이지(size)만큼 즉시 소비합니다.
    override suspend fun search(query: BoardSearchQuery): List<BoardSearchHit> =
        boardSearchPort.search(query.keyword, query.size).toList()

    // DB(정본)를 키셋 페이지네이션으로 순회하며 ES에 다시 색인합니다.
    // 한 번에 REINDEX_PAGE_SIZE건만 메모리에 올리므로 데이터가 커져도 일정한 메모리로 동작합니다.
    // - 색인은 페이지 단위 벌크(indexAll)로 수행해 건별 왕복을 없앱니다.
    // - 한 페이지 색인이 실패해도 전체를 중단하지 않고 그 페이지만 건너뛰며 실패 건수를 집계합니다
    //   (플러시/아카이브 배치와 같은 내결함성 철학). 단, CancellationException은 다시 던집니다.
    override suspend fun reindexAll(): ReindexResult {
        var cursor: Long? = null
        var indexed = 0L
        var failed = 0L
        while (true) {
            val page = boardRepositoryPort.findPage(cursor, REINDEX_PAGE_SIZE).toList()
            if (page.isEmpty()) break
            try {
                indexed += boardSearchPort.indexAll(page)
            } catch (e: CancellationException) {
                throw e
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
        log.info("reindexAll completed: indexed={}, failed={}", indexed, failed)
        return ReindexResult(indexed = indexed, failed = failed)
    }

    companion object {
        private const val REINDEX_PAGE_SIZE = 500
    }
}
