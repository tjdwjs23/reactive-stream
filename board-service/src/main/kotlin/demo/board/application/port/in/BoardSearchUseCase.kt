package demo.board.application.port.`in`

import demo.board.application.port.out.BoardSearchHit

// 5. 게시글 검색 유즈케이스 (한글 전문검색 + 관련도순 정렬)
// 반환 타입 BoardSearchHit은 out-port에 정의돼 있지만 둘 다 application 계층이므로
// (in-port ↔ out-port는 같은 층) 계층 규칙 위반이 아닙니다 — 불필요한 중복 DTO를 피합니다.
interface SearchBoardUseCase {
    suspend fun search(query: BoardSearchQuery): List<BoardSearchHit>
}

// 검색 요청. keyword는 필수(공백 불가), size는 1~100.
data class BoardSearchQuery(
    val keyword: String,
    val size: Int = 20,
) {
    init {
        require(keyword.isNotBlank()) { "검색어는 비어있을 수 없습니다." }
        require(size in 1..100) { "size는 1에서 100 사이여야 합니다." }
    }
}

// 6. 전체 재색인 유즈케이스: DB(정본)를 순회하며 ES 색인을 다시 채웁니다.
// 이벤트 유실(발행 실패/DLQ 격리)로 색인이 누락됐거나 인덱스를 새로 만들었을 때 정합성을 회복하는 용도.
interface ReindexBoardsUseCase {
    suspend fun reindexAll(): ReindexResult
}

// 재색인 결과. indexed=색인 성공 건수, failed=색인 실패(건너뛴) 건수.
// 페이지 단위로 벌크 색인하다 실패한 페이지는 건너뛰므로, 실패분을 별도로 노출해 재시도 판단에 씁니다.
data class ReindexResult(
    val indexed: Long,
    val failed: Long,
)
