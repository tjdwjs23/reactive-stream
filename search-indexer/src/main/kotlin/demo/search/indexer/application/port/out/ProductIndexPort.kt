package demo.search.indexer.application.port.out

import demo.search.indexer.domain.IndexedProduct

// 검색 인덱스(products)에 상품을 반영하는 out-port(BoardIndexPort와 대칭). 배치 단위 벌크 연산만 노출합니다.
interface ProductIndexPort {
    fun saveAll(products: List<IndexedProduct>)

    fun deleteAllById(productIds: List<Long>)
}
