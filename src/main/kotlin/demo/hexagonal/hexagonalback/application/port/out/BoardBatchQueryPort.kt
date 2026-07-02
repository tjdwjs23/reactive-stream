package demo.hexagonal.hexagonalback.application.port.out

import demo.hexagonal.hexagonalback.domain.model.Board
import java.time.LocalDateTime

// 대용량 배치 전용 out-port입니다. 일반 CRUD용 BoardRepositoryPort와 분리해(ISP)
// "전체를 List로 올리는" findAll()이 배치 경로로 새어 들어오지 않게 합니다.
interface BoardBatchQueryPort {
    // 스트리밍 조회: 전체를 메모리에 올리지 않고 pageSize 단위로 흘려보냅니다.
    // (murray UserReader가 Redis Cursor로 하던 것을 키셋 페이지네이션으로 구현)
    fun findStaleBoards(
        before: LocalDateTime,
        pageSize: Int,
    ): Sequence<Board>

    // 청크 단위 벌크 삭제. 반환값은 실제 삭제된 행 수입니다.
    fun deleteByIds(ids: List<Long>): Int
}
