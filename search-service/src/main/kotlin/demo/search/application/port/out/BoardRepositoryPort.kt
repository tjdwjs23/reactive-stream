package demo.search.application.port.out

import demo.search.domain.model.Board

// 도메인이 "나는 데이터를 저장하고 조회하고 싶어"라고 외치는 인터페이스입니다.
// 구체적으로 DB를 쓸지 파일시스템을 쓸지는 도메인은 모릅니다.
// MVC + JPA(블로킹) 스택이라 단건/다건 모두 평범한 블로킹 함수입니다(가상 스레드 위에서 실행).
interface BoardRepositoryPort {
    fun save(board: Board): Board

    fun findById(id: Long): Board?

    // 키셋 페이지네이션 조회: cursor(마지막으로 본 id) 이전(과거) 데이터를 id 내림차순으로 최대 limit건.
    // cursor가 null이면 최신부터. hasNext 판정을 위해 호출 측이 limit을 size+1로 넘길 수 있습니다.
    fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Board>

    fun deleteById(id: Long)

    // 조회수 write-back(배치): 여러 게시글의 view_count 델타를 단일 UPDATE로 한꺼번에 반영합니다.
    // 건별 왕복 대신 DB 라운드트립을 1회로 줄여, 플러시 대상이 많을수록 큰 이득입니다.
    // 반환값은 실제 반영된(존재하는) 게시글들의 최신 상태입니다(UPDATE ... RETURNING). 존재하지 않는 id는
    // 반영되지 않아 제외되므로 결과 크기는 deltas.size보다 작을 수 있습니다. 플러시가 이 최신 상태로
    // UPDATED 아웃박스 이벤트를 남겨 검색 색인(ES)의 조회수도 최종적으로 동기화합니다.
    fun addViewCountsBatch(deltas: Map<Long, Long>): List<Board>
}
