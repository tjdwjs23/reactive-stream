package demo.search.domain.model

import java.time.LocalDateTime

// 상품 도메인 모델(순수 Kotlin, 프레임워크 무의존). 짧은 이름의 초성/자동완성 검색이 목적이라 Board와 달리
// 조회수/작성자(소유권)가 없습니다 — 상품은 관리자가 관리하는 카탈로그성 데이터입니다.
// 불변 data class이며, JPA/ES 애노테이션은 절대 여기 붙지 않습니다(어댑터의 엔티티/문서와 매퍼로 분리).
data class Product(
    val id: Long? = null,
    val name: String,
    // 가격(원).
    val price: Long,
    // 생성 시각은 도메인이 벽시계를 직접 읽지 않고 생성 경계(서비스가 주입한 Clock)에서 주입받습니다(Board와 동일 원칙).
    val createdAt: LocalDateTime,
) {
    companion object {
        // 이름 최대 길이(도메인 불변식). DB products.name VARCHAR(255)와 정합 — 초과 시 400(raw DB 500 방지).
        // 생성 커맨드(CreateProductCommand)가 이 단일 소스를 공유합니다.
        const val MAX_NAME_LENGTH = 255
    }
}
