package demo.board.application.port.out

import demo.board.domain.model.Board
import kotlinx.coroutines.flow.Flow

// 도메인이 "게시글을 검색 인덱스에 반영하고, 키워드로 찾고 싶어"라고 외치는 인터페이스입니다.
// 저장소가 Elasticsearch인지 무엇인지는 도메인/서비스가 모릅니다(포트-어댑터 경계).
// 다건 검색 결과는 프로젝트 관례대로 List가 아닌 Flow로 흘려보냅니다.
interface BoardSearchPort {
    // 벌크 색인(upsert): 여러 건을 한 번의 요청으로 색인합니다. 전체 재색인에서 건별 왕복을 피하려고 씁니다.
    // 반환값은 색인에 성공한 문서 수. (단건 색인/삭제는 이벤트 소비자인 search-indexer가 담당하므로 이 포트에 없습니다.)
    suspend fun indexAll(boards: List<Board>): Int

    // 정본(DB)에 더 이상 없는 색인 문서(고아)를 제거합니다. keepIds = 재색인 시점에 DB에 존재하는 전체 게시글 id.
    // 재색인은 upsert만 하므로, 삭제 이벤트 유실 등으로 남은 ES 고아 문서는 이 정리로만 회복됩니다.
    // 재색인 도중 갓 생성돼 아직 keepIds 스냅샷에 없는 문서까지 지우지 않도록, max(keepIds) 이하의 문서만 대상으로 합니다
    // (keepIds가 비면 정본이 비었다는 뜻이라 전체 삭제). 반환값은 삭제한 고아 문서 수입니다.
    suspend fun pruneExcept(keepIds: Set<Long>): Int

    // 키워드 전문검색. Nori로 형태소 분석된 title/content를 대상으로 매칭하고,
    // 관련도(_score) 내림차순으로 최대 size건을 흘려보냅니다.
    fun search(
        keyword: String,
        size: Int,
    ): Flow<BoardSearchHit>
}

// 검색 한 건의 결과. 도메인 Board + 관련도 점수 + 하이라이트(매칭 부분에 <em> 태그).
// score/highlight는 검색 인프라(ES)의 산출물이지만, 포트의 반환 계약으로 애플리케이션 계층에 둡니다.
data class BoardSearchHit(
    val board: Board,
    val score: Double,
    val highlightedTitle: String?,
    val highlightedContent: String?,
)
