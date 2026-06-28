package demo.hexagonal.hexagonalback.adapter.`in`.web

import java.time.LocalDateTime

data class CreateBoardRequest(
    val title: String,
    val content: String
)

data class UpdateBoardRequest(
    val title: String,
    val content: String
)

data class BoardResponse(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime
)
