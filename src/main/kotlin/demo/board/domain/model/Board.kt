package demo.board.domain.model

import demo.board.domain.exception.BoardValidationException
import java.time.LocalDateTime

data class Board(
    val id: Long? = null,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // 조회수. DB에 누적된 값이며, 조회 응답 시 Redis에 아직 반영 안 된 델타를 더해 실시간 값으로 보정합니다.
    val viewCount: Long = 0,
) {
    fun update(
        title: String,
        content: String,
    ): Board {
        if (title.isBlank()) throw BoardValidationException("제목은 비어있을 수 없습니다.")
        if (title.length > MAX_TITLE_LENGTH) throw BoardValidationException("제목은 ${MAX_TITLE_LENGTH}자를 넘을 수 없습니다.")
        return this.copy(title = title, content = content)
    }

    // "이 게시글이 보관 기간을 넘겨 아카이브 대상인가?"라는 판단은 도메인의 책임입니다.
    // 배치/웹 어디에서 호출하든 규칙은 여기 한 곳에만 존재합니다.
    fun isStale(
        now: LocalDateTime,
        retentionDays: Long,
    ): Boolean = createdAt.isBefore(now.minusDays(retentionDays))

    companion object {
        // 제목 최대 길이(도메인 불변식). DB 스키마 title VARCHAR(255)와 정합하며,
        // 생성(CreateBoardCommand)·수정(update) 검증이 이 단일 소스를 공유합니다.
        const val MAX_TITLE_LENGTH = 255
    }
}
