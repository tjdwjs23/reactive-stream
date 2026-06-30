package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.application.port.out.BoardRepositoryPort
import demo.hexagonal.hexagonalback.domain.model.Board
import org.springframework.stereotype.Repository

@Repository // 스프링 빈으로 등록
class BoardPersistenceAdapter(
    private val boardJpaRepository: BoardJpaRepository,
    private val boardMapper: BoardMapper,
) : BoardRepositoryPort {
    override fun save(board: Board): Board {
        // 1. 도메인을 엔티티로 변환
        val entity = boardMapper.toEntity(board)
        // 2. JPA로 저장
        val savedEntity = boardJpaRepository.save(entity)
        // 3. 다시 도메인으로 변환해서 반환
        return boardMapper.toDomain(savedEntity)
    }

    override fun findById(id: Long): Board? =
        boardJpaRepository
            .findById(id)
            .map { boardMapper.toDomain(it) }
            .orElse(null)

    override fun findAll(): List<Board> =
        boardJpaRepository
            .findAll()
            .map { boardMapper.toDomain(it) }

    override fun deleteById(id: Long) {
        boardJpaRepository.deleteById(id)
    }
}
