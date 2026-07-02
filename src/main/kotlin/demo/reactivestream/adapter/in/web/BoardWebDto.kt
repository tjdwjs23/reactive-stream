package demo.reactivestream.adapter.`in`.web

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
)

// 키셋 페이지 응답. 클라이언트는 hasNext가 true일 때 nextCursor를 cursor 파라미터로 다시 넘겨 다음 페이지를 받습니다.
data class BoardPageResponse(
    val items: List<BoardResponse>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)
