package demo.reactivestream.application.port.`in`

import demo.reactivestream.application.port.out.BoardSearchHit

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
        require(keyword.isNotBlank()) { "keyword must not be blank" }
        require(size in 1..100) { "size must be between 1 and 100" }
    }
}

// 6. 전체 재색인 유즈케이스: DB(정본)를 순회하며 ES 색인을 다시 채웁니다.
// 인라인 색인이 실패로 누락됐거나 인덱스를 새로 만들었을 때 정합성을 회복하는 용도.
interface ReindexBoardsUseCase {
    // 반환값: 재색인된 게시글 수
    suspend fun reindexAll(): Long
}
