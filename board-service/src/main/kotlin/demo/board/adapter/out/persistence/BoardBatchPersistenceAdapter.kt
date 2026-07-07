package demo.board.adapter.out.persistence

import demo.board.application.port.out.BoardBatchQueryPort
import demo.board.domain.model.Board
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

// BoardBatchQueryPort의 구현체. 일반 CRUD용 BoardPersistenceAdapter와 분리해
// 배치 특유의 관심사(스트리밍 읽기, 벌크 삭제)만 담습니다.
@Repository
class BoardBatchPersistenceAdapter(
    private val boardR2dbcRepository: BoardR2dbcRepository,
    private val boardMapper: BoardMapper,
) : BoardBatchQueryPort {
    // 키셋 페이지네이션으로 한 페이지씩 지연 생성하는 Flow.
    // 전체를 한 번에 메모리에 올리지 않으며, 소비자가 요청할 때만(백프레셔) 다음 페이지를 읽습니다.
    // 페이지 조회 각각이 독립된 짧은 읽기라 스캔 도중 삭제가 진행돼도 오래 유지되는 커서가 없습니다.
    override fun findStaleBoards(
        before: LocalDateTime,
        pageSize: Int,
    ): Flow<Board> =
        flow {
            var lastId = 0L
            while (true) {
                val page = boardR2dbcRepository.findStalePage(before, lastId, pageSize).toList()
                if (page.isEmpty()) break
                page.forEach { emit(boardMapper.toDomain(it)) }
                lastId = page.last().id ?: break
                if (page.size < pageSize) break
            }
        }

    // 단일 DELETE 문(WHERE id IN (:ids))이라 그 자체로 원자적이고 R2DBC 오토커밋으로 짧게 커밋됩니다.
    // 프로젝트 원칙("단일 오토커밋 문장을 트랜잭션으로 감싸는 건 이득이 없다" — BoardService 참고)에 따라
    // @Transactional을 붙이지 않습니다. 여러 문장을 원자적으로 묶어야 하는 흐름이 생기면 그때만 좁게 붙입니다.
    override suspend fun deleteByIds(ids: List<Long>): Int = boardR2dbcRepository.deleteByIds(ids)
}
