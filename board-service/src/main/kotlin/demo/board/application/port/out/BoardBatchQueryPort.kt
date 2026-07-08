package demo.board.application.port.out

import demo.board.domain.model.Board
import java.time.LocalDateTime

// 대용량 배치 전용 out-port입니다. 일반 CRUD용 BoardRepositoryPort와 분리해(ISP)
// "전체를 List로 올리는" findAll()이 배치 경로로 새어 들어오지 않게 합니다.
interface BoardBatchQueryPort {
    // 키셋(seek) 페이지네이션 한 페이지: created_at < before AND id > lastId 를 id 오름차순으로 pageSize건.
    // 블로킹 스택이라 Flow 대신 페이지 단위 List를 반환하고, 스트리밍(전체를 메모리에 올리지 않기)과
    // 백프레셔는 호출자(ArchiveStaleBoardsService)가 이 페이지를 코루틴 Channel로 흘려보내며 구현합니다.
    fun findStalePage(
        before: LocalDateTime,
        lastId: Long,
        pageSize: Int,
    ): List<Board>

    // 청크 단위 벌크 삭제. 반환값은 실제 삭제된 행 수입니다.
    fun deleteByIds(ids: List<Long>): Int
}
