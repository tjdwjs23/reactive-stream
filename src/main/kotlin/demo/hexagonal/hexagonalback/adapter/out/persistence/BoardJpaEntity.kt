// adapter/out/persistence/BoardJpaEntity.kt
package demo.hexagonal.hexagonalback.adapter.out.persistence

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "board")
class BoardJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)