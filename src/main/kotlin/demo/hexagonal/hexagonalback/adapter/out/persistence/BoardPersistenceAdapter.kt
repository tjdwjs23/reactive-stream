package demo.hexagonal.hexagonalback.adapter.out.persistence

import demo.hexagonal.hexagonalback.application.port.out.BoardRepositoryPort
import demo.hexagonal.hexagonalback.domain.model.Board
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.stereotype.Repository

@Repository // 스프링 빈으로 등록
class BoardPersistenceAdapter(
    private val boardR2dbcRepository: BoardR2dbcRepository,
    private val boardMapper: BoardMapper,
) : BoardRepositoryPort {
    override suspend fun save(board: Board): Board {
        // 1. 도메인을 엔티티로 변환
        val entity = boardMapper.toEntity(board)
        // 2. R2DBC로 저장 (id가 채번된 엔티티가 반환됨)
        val savedEntity = boardR2dbcRepository.save(entity)
        // 3. 다시 도메인으로 변환해서 반환
        return boardMapper.toDomain(savedEntity)
    }

    override suspend fun findById(id: Long): Board? =
        boardR2dbcRepository.findById(id)?.let { boardMapper.toDomain(it) }

    // 논블로킹 스트림. 원소가 흘러올 때마다 도메인으로 변환해 흘려보냅니다.
    override fun findAll(): Flow<Board> = boardR2dbcRepository.findAll().map { boardMapper.toDomain(it) }

    override suspend fun deleteById(id: Long) {
        boardR2dbcRepository.deleteById(id)
    }
}
