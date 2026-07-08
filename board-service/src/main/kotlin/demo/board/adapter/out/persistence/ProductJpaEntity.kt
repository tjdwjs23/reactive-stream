package demo.board.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// JPA(Hibernate) 매핑 대상(products 테이블). 도메인 Product ↔ 이 엔티티 변환은 ProductMapper가 담당합니다
// (프레임워크 애노테이션이 도메인으로 새지 않도록 — Board와 동일한 격리 규칙).
@Entity
@Table(name = "products")
class ProductJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var price: Long,
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,
)
