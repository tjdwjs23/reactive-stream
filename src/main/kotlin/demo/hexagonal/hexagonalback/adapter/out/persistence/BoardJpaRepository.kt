package demo.hexagonal.hexagonalback.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface BoardJpaRepository : JpaRepository<BoardJpaEntity, Long>
