package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.application.port.out.BoardBatchQueryPort
import demo.hexagonal.hexagonalback.domain.model.Board
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
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

    // 청크 단위로 짧게 커밋합니다(배치 전체를 하나의 트랜잭션으로 묶지 않음).
    // R2DBC에서는 Spring이 구성한 ReactiveTransactionManager를 통해 트랜잭션이 적용됩니다.
    @Transactional
    override suspend fun deleteByIds(ids: List<Long>): Int = boardR2dbcRepository.deleteByIdIn(ids)
}
