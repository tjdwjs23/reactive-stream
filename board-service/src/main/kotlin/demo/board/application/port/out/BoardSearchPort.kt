package demo.board.application.port.out

import demo.board.domain.model.Board

// 도메인이 "게시글을 검색 인덱스에 반영하고, 키워드로 찾고 싶어"라고 외치는 인터페이스입니다.
// 저장소가 Elasticsearch인지 무엇인지는 도메인/서비스가 모릅니다(포트-어댑터 경계).
// MVC 스택이라 블로킹 함수이며, 다건 검색 결과는 List로 반환합니다.
interface BoardSearchPort {
    // 키워드 전문검색. Nori로 형태소 분석된 title/content를 대상으로 매칭하고,
    // 관련도(_score) 내림차순으로 최대 size건을 반환합니다. 읽기는 alias('boards')를 통해 현재 활성 버전 인덱스로 향합니다.
    fun search(
        keyword: String,
        size: Int,
    ): List<BoardSearchHit>

    // ── Alias 기반 무중단 재색인 ────────────────────────────────────────────────
    // 검색은 'boards' alias로만 접근하고, 실제 데이터는 뒤의 버전 인덱스('boards_v1', 'boards_<ts>')에 있습니다.
    // 재색인은 (1) 새 버전 인덱스를 만들어 거기에 전량을 다시 채운 뒤 (2) alias를 원자적으로 새 버전으로 옮기고
    // (3) 옛 버전을 지웁니다. 스왑 전까지 검색은 옛 인덱스를 그대로 보므로 무중단이고, 스왑을 안 하면 자동 롤백입니다.
    // (단건 색인/삭제는 이벤트 소비자인 search-indexer가 alias에 반영하므로 이 포트엔 없습니다.)

    // 현재 매핑/설정으로 새 버전 인덱스를 만들고 그 이름을 반환합니다(아직 alias는 옮기지 않음).
    fun createNewVersionIndex(): String

    // 지정한 버전 인덱스에 벌크 색인(upsert)합니다. 반환값은 색인한 문서 수.
    fun indexInto(
        boards: List<Board>,
        indexName: String,
    ): Int

    // alias('boards')를 지정한 버전 인덱스로 원자적으로 이동하고(쓰기 인덱스로 지정), 다른 버전 인덱스는 삭제합니다.
    fun promote(indexName: String)

    // 반쯤 만들다 실패한 버전 인덱스를 삭제합니다(스왑하지 않고 롤백).
    fun deleteVersionIndex(indexName: String)
}

// 검색 한 건의 결과. 도메인 Board + 관련도 점수 + 하이라이트(매칭 부분에 <em> 태그).
// score/highlight는 검색 인프라(ES)의 산출물이지만, 포트의 반환 계약으로 애플리케이션 계층에 둡니다.
data class BoardSearchHit(
    val board: Board,
    val score: Double,
    val highlightedTitle: String?,
    val highlightedContent: String?,
)
