package demo.search.indexer.application.port.`in`

import demo.search.events.ProductChangedEvent

// 상품 변경 이벤트 배치를 검색 인덱스(products)에 반영하는 유스케이스(ApplyBoardChangeUseCase와 대칭).
interface ApplyProductChangeUseCase {
    fun applyAll(events: List<ProductChangedEvent>)
}
