package demo.hexagonal.hexagonalback.domain.model

import demo.hexagonal.hexagonalback.domain.exception.BoardValidationException
import java.time.LocalDateTime

data class Board(
    val id: Long? = null,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    fun update(title: String, content: String): Board {
        if (title.isBlank()) throw BoardValidationException("제목은 비어있을 수 없습니다.")
        return this.copy(title = title, content = content)
    }
}