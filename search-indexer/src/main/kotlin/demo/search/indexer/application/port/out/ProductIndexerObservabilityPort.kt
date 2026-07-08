package demo.search.indexer.application.port.out

// 상품 색인 파이프라인의 운영 사건을 기록하는 out-port(IndexerObservabilityPort와 대칭, product.indexer.* 네임스페이스).
// board 인덱서 관측 포트/어댑터/테스트를 건드리지 않도록 분리합니다.
interface ProductIndexerObservabilityPort {
    fun recordIndexingBatch(block: () -> Unit)

    fun productsIndexed(count: Int)

    fun productsDeleted(count: Int)

    fun messageDeadLettered()
}
