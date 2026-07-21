package demo.search.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// JPA(Hibernate) 매핑 대상(board 테이블). 도메인 Board ↔ 이 엔티티 변환은 BoardMapper가 담당합니다
// (프레임워크 애노테이션이 도메인으로 새지 않도록).
//
// kotlin("plugin.jpa")가 no-arg 생성자 + allopen(프록시)을 부여하므로 별도 기본 생성자를 두지 않습니다.
// 필드는 var로 둡니다(Hibernate가 로드 시 필드를 채우고, id는 IDENTITY로 채번). createdAt의 값은 도메인이
// 생성 시점에 부여하는 단일 소스라 DB DEFAULT 없이 매퍼가 항상 넘깁니다.
@Entity
@Table(name = "board")
class BoardJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false)
    var title: String,
    // content는 TEXT 컬럼(길이 무제한). columnDefinition으로 ddl-auto=validate가 VARCHAR로 오판하지 않게 명시합니다.
    @Column(nullable = false, columnDefinition = "text")
    var content: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,
    @Column(name = "view_count", nullable = false)
    var viewCount: Long = 0,
    @Column(name = "author_id")
    var authorId: Long? = null,
)
