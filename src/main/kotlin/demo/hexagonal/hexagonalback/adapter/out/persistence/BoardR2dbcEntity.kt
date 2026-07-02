package demo.hexagonal.hexagonalback.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

// Spring Data R2DBC 매핑 대상. JPA와 달리 프록시/영속성 컨텍스트가 없는 단순 값 홀더입니다.
// id가 null이면 신규(INSERT)로 간주되어 DB IDENTITY로 채번되고, 저장 후 채번된 id가 채워집니다.
// createdAt(camelCase) ↔ created_at(snake_case)은 R2DBC 기본 네이밍 전략이 자동 매핑합니다.
@Table("board")
class BoardR2dbcEntity(
    @Id
    val id: Long? = null,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
