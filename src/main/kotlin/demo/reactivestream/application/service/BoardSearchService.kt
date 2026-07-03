package demo.reactivestream.application.service

import demo.reactivestream.application.port.`in`.BoardSearchQuery
import demo.reactivestream.application.port.`in`.ReindexBoardsUseCase
import demo.reactivestream.application.port.`in`.SearchBoardUseCase
import demo.reactivestream.application.port.out.BoardRepositoryPort
import demo.reactivestream.application.port.out.BoardSearchHit
import demo.reactivestream.application.port.out.BoardSearchPort
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
    override suspend fun reindexAll(): Long {
        var cursor: Long? = null
        var total = 0L
        while (true) {
            val page = boardRepositoryPort.findPage(cursor, REINDEX_PAGE_SIZE).toList()
            if (page.isEmpty()) break
            page.forEach { boardSearchPort.index(it) }
            total += page.size
            cursor = page.last().id
            if (page.size < REINDEX_PAGE_SIZE) break
        }
        log.info("reindexAll completed: {} boards indexed", total)
        return total
    }

    companion object {
        private const val REINDEX_PAGE_SIZE = 500
    }
}
