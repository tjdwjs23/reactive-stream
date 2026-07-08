package demo.search.application.service

import demo.search.application.port.`in`.CreateProductCommand
import demo.search.application.port.`in`.CreateProductUseCase
import demo.search.application.port.`in`.DeleteProductUseCase
import demo.search.application.port.`in`.GetProductUseCase
import demo.search.application.port.`in`.ProductPage
import demo.search.application.port.`in`.ProductPageQuery
import demo.search.application.port.out.ProductEventOutboxPort
import demo.search.application.port.out.ProductObservabilityPort
import demo.search.application.port.out.ProductRepositoryPort
import demo.search.application.port.out.TransactionRunnerPort
import demo.search.domain.exception.ProductNotFoundException
import demo.search.domain.model.Product
import demo.search.events.ProductChangeType
import demo.search.events.ProductChangedEvent
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// 상품 정본(DB) 쓰기 + 조회 서비스. BoardService와 동일한 원칙:
// - 클래스 레벨 @Transactional 없음. 조회는 트랜잭션 없는 단일 SELECT.
// - 단 하나의 예외가 "쓰기 + 아웃박스 기록"으로, TransactionRunnerPort로 원자화(Transactional Outbox).
// - 검색 색인은 쓰기 경로에서 하지 않고, 아웃박스 이벤트를 Kafka로 발행하면 search-indexer가 products 인덱스에 반영합니다.
// 상품은 소유권이 없어(관리자 관리형) 인가 검사가 없습니다 — 쓰기 접근은 SecurityConfig(ROLE_ADMIN)가 통제합니다.
@Service
class ProductService(
    private val productRepositoryPort: ProductRepositoryPort,
    private val productEventOutboxPort: ProductEventOutboxPort,
    private val transactionRunner: TransactionRunnerPort,
    private val observability: ProductObservabilityPort,
    private val clock: Clock,
) : CreateProductUseCase,
    GetProductUseCase,
    DeleteProductUseCase {
    override fun createProduct(command: CreateProductCommand): Product {
        val newProduct =
            Product(
                name = command.name,
                price = command.price,
                createdAt = LocalDateTime.now(clock),
            )
        val saved =
            transactionRunner.execute {
                val persisted = productRepositoryPort.save(newProduct)
                productEventOutboxPort.record(persisted.toChangedEvent(ProductChangeType.CREATED))
                persisted
            }
        observability.productCreated()
        return saved
    }

    override fun getProduct(id: Long): Product =
        productRepositoryPort.findById(id) ?: throw ProductNotFoundException(id)

    override fun getProducts(query: ProductPageQuery): ProductPage {
        val rows = productRepositoryPort.findPage(query.cursor, query.size + 1)
        val hasNext = rows.size > query.size
        val items = if (hasNext) rows.take(query.size) else rows
        return ProductPage(
            items = items,
            nextCursor = if (hasNext) items.last().id else null,
            hasNext = hasNext,
        )
    }

    override fun deleteProduct(id: Long) {
        // 없으면 404. 삭제(DELETE) + 아웃박스 기록(DELETED)을 하나의 트랜잭션으로 묶습니다.
        productRepositoryPort.findById(id) ?: throw ProductNotFoundException(id)
        transactionRunner.execute {
            productRepositoryPort.deleteById(id)
            productEventOutboxPort.record(deletedEvent(id))
        }
        observability.productDeleted()
    }

    private fun Product.toChangedEvent(type: ProductChangeType): ProductChangedEvent =
        ProductChangedEvent(
            eventId = UUID.randomUUID().toString(),
            productId = requireNotNull(id) { "저장된 상품은 id가 있어야 합니다" },
            type = type,
            name = name,
            price = price,
            createdAt = createdAt,
            occurredAt = Instant.now(clock),
        )

    private fun deletedEvent(productId: Long): ProductChangedEvent =
        ProductChangedEvent(
            eventId = UUID.randomUUID().toString(),
            productId = productId,
            type = ProductChangeType.DELETED,
            occurredAt = Instant.now(clock),
        )
}
