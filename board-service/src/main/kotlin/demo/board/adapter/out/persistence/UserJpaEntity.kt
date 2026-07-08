package demo.board.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// JPA 매핑 대상(users 테이블). BoardJpaEntity와 동일한 단순 매핑 규칙입니다.
// role은 DB에 문자열('USER'/'ADMIN')로 저장되며, 도메인 Role enum과의 변환은 UserMapper가 담당합니다.
@Entity
@Table(name = "users")
class UserJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true, length = 50)
    var username: String,
    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String,
    @Column(nullable = false, length = 20)
    var role: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,
)
