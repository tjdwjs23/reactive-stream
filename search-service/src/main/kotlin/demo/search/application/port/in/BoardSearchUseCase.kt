package demo.search.application.port.`in`

import demo.search.application.port.out.BoardSearchHit

// 5. 게시글 검색 유즈케이스 (한글 전문검색 + 관련도순 정렬)
// 반환 타입 BoardSearchHit은 out-port에 정의돼 있지만 둘 다 application 계층이므로
// (in-port ↔ out-port는 같은 층) 계층 규칙 위반이 아닙니다 — 불필요한 중복 DTO를 피합니다.
interface SearchBoardUseCase {
    fun search(query: BoardSearchQuery): List<BoardSearchHit>
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

// 6. 전체 재색인 유즈케이스: DB(정본)를 새 버전 인덱스에 재구축한 뒤 alias를 원자적으로 스왑합니다(무중단).
// 이벤트 유실(발행 실패/DLQ 격리)로 색인이 누락됐거나 매핑을 바꿔 인덱스를 다시 만들 때 정합성을 회복하는 용도.
// 새 인덱스로 깨끗이 재구축하므로 과거 방식의 고아(orphan) 정리(prune)가 원천적으로 불필요합니다.
interface ReindexBoardsUseCase {
    fun reindexAll(): ReindexResult
}

// 재색인 결과. indexed=색인 성공 건수, failed=색인 실패(건너뛴) 건수, swapped=alias를 새 인덱스로 스왑했는지.
// 페이지 단위 벌크 색인 중 한 페이지라도 실패하면(failed>0) 새 인덱스가 불완전하므로 alias를 옮기지 않고(swapped=false)
// 새 인덱스를 폐기합니다 — 검색은 기존 인덱스를 그대로 보며(무중단·자동 롤백), 관리자는 재시도할 수 있습니다.
data class ReindexResult(
    val indexed: Long,
    val failed: Long,
    val swapped: Boolean,
)
