package demo.board.domain.exception

// 상품 도메인 예외. 상품은 소유권이 없어 접근 거부(IDOR) 예외가 없고, 없는 상품 조회/삭제 시 404만 냅니다.
// (입력값 검증은 CreateProductCommand.init의 require → IllegalArgumentException → 400으로 매핑됩니다.)
class ProductNotFoundException(
    id: Long,
) : RuntimeException("Product not found with id: $id")
