package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.application.port.out.BoardBatchQueryPort
import demo.hexagonal.hexagonalback.domain.model.Board
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// BoardBatchQueryPort의 구현체. 일반 CRUD용 BoardPersistenceAdapter와 분리해
// 배치 특유의 관심사(스트리밍 읽기, 벌크 삭제)만 담습니다.
@Repository
class BoardBatchPersistenceAdapter(
    private val boardJpaRepository: BoardJpaRepository,
    private val boardMapper: BoardMapper,
) : BoardBatchQueryPort {
    // 키셋 페이지네이션으로 한 페이지씩 지연 생성하는 Sequence.
    // 전체를 한 번에 메모리에 올리지 않으며, 소비자가 요청할 때만 다음 페이지를 읽습니다.
    // 페이지 조회 각각이 Spring Data 기본 트랜잭션(짧은 read)으로 독립 실행되므로
    // 스캔 도중 삭제가 진행돼도 오래 유지되는 커서/트랜잭션이 없습니다.
    override fun findStaleBoards(
        before: LocalDateTime,
        pageSize: Int,
    ): Sequence<Board> =
        sequence {
            var lastId = 0L
            while (true) {
                val page = boardJpaRepository.findStalePage(before, lastId, PageRequest.of(0, pageSize))
                if (page.isEmpty()) break
                page.forEach { yield(boardMapper.toDomain(it)) }
                lastId = page.last().id ?: break
                if (page.size < pageSize) break
            }
        }

    // 청크 단위로 짧게 커밋합니다(배치 전체를 하나의 트랜잭션으로 묶지 않음).
    @Transactional
    override fun deleteByIds(ids: List<Long>): Int = boardJpaRepository.deleteByIdIn(ids)
}
