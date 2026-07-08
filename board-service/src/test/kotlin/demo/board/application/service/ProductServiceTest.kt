package demo.board.application.service

import demo.board.application.port.`in`.CreateProductCommand
import demo.board.application.port.`in`.ProductPageQuery
import demo.board.application.port.out.ProductEventOutboxPort
import demo.board.application.port.out.ProductRepositoryPort
import demo.board.application.port.out.TransactionRunnerPort
import demo.board.domain.exception.ProductNotFoundException
import demo.board.domain.model.Product
import demo.board.events.ProductChangeType
import demo.board.events.ProductChangedEvent
import demo.board.support.NoOpProductObservabilityPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// 인메모리 페이크로 상품 쓰기 경로(저장+아웃박스 원자 기록)와 조회/삭제/페이지네이션을 결정적으로 검증합니다.
private class FakeProductRepositoryPort : ProductRepositoryPort {
    private val store = LinkedHashMap<Long, Product>()
    private var seq = 0L

    override fun save(product: Product): Product {
        val id = product.id ?: ++seq
        val persisted = product.copy(id = id)
        store[id] = persisted
        return persisted
    }

    override fun findById(id: Long): Product? = store[id]

    override fun deleteById(id: Long) {
        store.remove(id)
    }

    override fun findPage(
        cursor: Long?,
        limit: Int,
    ): List<Product> =
        store.values
            .sortedByDescending { it.id!! }
            .filter { cursor == null || it.id!! < cursor }
            .take(limit)
}

private class FakeProductEventOutboxPort : ProductEventOutboxPort {
    val recorded = mutableListOf<ProductChangedEvent>()

    override fun record(event: ProductChangedEvent) {
        recorded.add(event)
    }
}

// 트랜잭션 경계 페이크: 블록을 그대로 실행(원자성은 통합테스트에서, 여기선 호출 순서/기록만 검증).
private val directTxRunner =
    object : TransactionRunnerPort {
        override fun <T> execute(block: () -> T): T = block()
    }

private val fixedClock = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC)

class ProductServiceTest :
    BehaviorSpec({

        Given("상품 생성 - createProduct") {
            val repo = FakeProductRepositoryPort()
            val outbox = FakeProductEventOutboxPort()
            val service = ProductService(repo, outbox, directTxRunner, NoOpProductObservabilityPort, fixedClock)

            When("유효한 커맨드로 생성하면") {
                val created = service.createProduct(CreateProductCommand(name = "삼각김밥", price = 1200))

                Then("id가 채번되고, 같은 트랜잭션에서 CREATED 아웃박스 이벤트가 기록된다") {
                    created.id.shouldNotBeNull()
                    created.name shouldBe "삼각김밥"
                    outbox.recorded.size shouldBe 1
                    outbox.recorded.first().type shouldBe ProductChangeType.CREATED
                    outbox.recorded.first().productId shouldBe created.id
                    outbox.recorded.first().name shouldBe "삼각김밥"
                }
            }
        }

        Given("상품 삭제 - deleteProduct") {
            val repo = FakeProductRepositoryPort()
            val outbox = FakeProductEventOutboxPort()
            val service = ProductService(repo, outbox, directTxRunner, NoOpProductObservabilityPort, fixedClock)
            val created = service.createProduct(CreateProductCommand(name = "삼계탕", price = 12000))

            When("존재하는 상품을 삭제하면") {
                service.deleteProduct(created.id!!)

                Then("삭제되고 DELETED 아웃박스 이벤트가 기록된다") {
                    repo.findById(created.id!!) shouldBe null
                    outbox.recorded.last().type shouldBe ProductChangeType.DELETED
                    outbox.recorded.last().productId shouldBe created.id
                }
            }

            When("없는 상품을 삭제하면") {
                Then("ProductNotFoundException(404)이 발생한다") {
                    shouldThrow<ProductNotFoundException> { service.deleteProduct(-1L) }
                }
            }
        }

        Given("상품 조회 - getProduct") {
            val repo = FakeProductRepositoryPort()
            val service =
                ProductService(
                    repo,
                    FakeProductEventOutboxPort(),
                    directTxRunner,
                    NoOpProductObservabilityPort,
                    fixedClock,
                )
            val created = service.createProduct(CreateProductCommand(name = "김밥", price = 3000))

            When("존재하는 id로 조회하면") {
                Then("상품을 반환한다") {
                    service.getProduct(created.id!!).name shouldBe "김밥"
                }
            }

            When("없는 id로 조회하면") {
                Then("ProductNotFoundException(404)이 발생한다") {
                    shouldThrow<ProductNotFoundException> { service.getProduct(-1L) }
                }
            }
        }

        Given("상품 목록 - getProducts(키셋 페이지네이션)") {
            val repo = FakeProductRepositoryPort()
            val service =
                ProductService(
                    repo,
                    FakeProductEventOutboxPort(),
                    directTxRunner,
                    NoOpProductObservabilityPort,
                    fixedClock,
                )
            val ids =
                (1..5).map {
                    service
                        .createProduct(
                            CreateProductCommand(name = "상품$it", price = it.toLong()),
                        ).id!!
                }

            When("size=2로 첫 페이지를 조회하면") {
                val page = service.getProducts(ProductPageQuery(cursor = null, size = 2))

                Then("id 내림차순 2건 + hasNext=true + nextCursor=마지막 id") {
                    page.items.map { it.id } shouldContainExactly listOf(ids[4], ids[3])
                    page.hasNext shouldBe true
                    page.nextCursor shouldBe ids[3]
                }
            }

            When("마지막 페이지를 조회하면") {
                val page = service.getProducts(ProductPageQuery(cursor = ids[1], size = 2))

                Then("남은 1건 + hasNext=false") {
                    page.items.map { it.id } shouldContainExactly listOf(ids[0])
                    page.hasNext shouldBe false
                    page.nextCursor shouldBe null
                }
            }
        }
    })
