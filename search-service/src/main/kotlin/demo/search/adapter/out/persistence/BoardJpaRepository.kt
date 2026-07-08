package demo.search.adapter.out.persistence

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

// Spring Data JPA 리포지토리. 기본 CRUD(save/findById/deleteById 등)는 JpaRepository가 제공하고,
// 동적/키셋 조회는 KotlinJdslJpqlExecutor(Kotlin JDSL)로 어댑터에서 타입세이프하게 작성합니다.
// (QueryDSL의 kapt 코드젠 대신, 순수 Kotlin DSL로 Q타입 없이 조합 — findPage/findStalePage).
interface BoardJpaRepository :
    JpaRepository<BoardJpaEntity, Long>,
    KotlinJdslJpqlExecutor
