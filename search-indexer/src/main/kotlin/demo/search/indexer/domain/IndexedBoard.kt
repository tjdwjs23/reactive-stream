package demo.search.indexer.domain

import java.time.LocalDateTime

// 색인 대상 게시글의 순수 표현. search-service의 도메인 Board와 대칭이지만, 이 서비스는 "색인"에만 관심이 있어
// 검색/복원에 필요한 필드만 가진다. ES 애노테이션(@Document 등)은 여기 새어 들어오지 않는다(어댑터의 BoardDocument로 변환).
data class IndexedBoard(
    val id: Long,
    val title: String,
    val content: String,
    val viewCount: Long,
    val createdAt: LocalDateTime,
    val authorId: Long?,
)
