package demo.search.adapter.out.persistence

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

// Spring Data JPA 리포지토리(상품). 기본 CRUD는 JpaRepository, 키셋 목록은 KotlinJdslJpqlExecutor로 어댑터에서 작성합니다.
interface ProductJpaRepository :
    JpaRepository<ProductJpaEntity, Long>,
    KotlinJdslJpqlExecutor
