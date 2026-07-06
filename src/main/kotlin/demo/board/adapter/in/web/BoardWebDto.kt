package demo.board.adapter.`in`.web

import java.time.LocalDateTime

data class CreateBoardRequest(
    val title: String,
    val content: String,
)

data class UpdateBoardRequest(
    val title: String,
    val content: String,
)

data class BoardResponse(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime,
    val viewCount: Long,
    // 작성자 id. 작성자 미상(기존 데이터)이면 null.
    val authorId: Long?,
)

// 키셋 페이지 응답. 클라이언트는 hasNext가 true일 때 nextCursor를 cursor 파라미터로 다시 넘겨 다음 페이지를 받습니다.
data class BoardPageResponse(
    val items: List<BoardResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)

// 검색 결과 한 건. board(원문) + score(관련도) + 매칭 부분을 <em>로 감싼 하이라이트.
data class BoardSearchItemResponse(
    val board: BoardResponse,
    val score: Double,
    val highlightedTitle: String?,
    val highlightedContent: String?,
)

// 검색 응답. items는 관련도(score) 내림차순으로 정렬돼 있습니다.
data class BoardSearchResponse(
    val keyword: String,
    val total: Int,
    val items: List<BoardSearchItemResponse>,
)
