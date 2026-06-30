// adapter/out/persistence/BoardJpaEntity.kt
package demo.hexagonal.hexagonalback.adapter.out.persistence

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "board")
class BoardJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
