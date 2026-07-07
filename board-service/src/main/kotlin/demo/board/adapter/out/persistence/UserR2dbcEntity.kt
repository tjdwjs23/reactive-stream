package demo.board.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

// Spring Data R2DBC 매핑 대상(users 테이블). BoardR2dbcEntity와 동일하게 단순 값 홀더입니다.
// role은 DB에 문자열('USER'/'ADMIN')로 저장되며, 도메인 Role enum과의 변환은 UserMapper가 담당합니다.
// username/passwordHash ↔ username/password_hash 등 camelCase↔snake_case는 R2DBC 기본 네이밍이 매핑합니다.
@Table("users")
class UserR2dbcEntity(
    @Id
    val id: Long? = null,
    val username: String,
    val passwordHash: String,
    val role: String,
    val createdAt: LocalDateTime,
)
