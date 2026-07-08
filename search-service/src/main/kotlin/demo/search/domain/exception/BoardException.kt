package demo.search.domain.exception

class BoardNotFoundException(
    id: Long,
) : RuntimeException("Board not found with id: $id")

class BoardValidationException(
    message: String,
) : RuntimeException(message)

// 소유자도 관리자도 아닌 사용자가 남의 게시글을 수정/삭제하려 할 때 발생합니다(403 Forbidden으로 매핑).
// 다른 사용자의 리소스를 id만으로 조작하는 IDOR(Insecure Direct Object Reference)를 차단합니다.
class BoardAccessDeniedException(
    val boardId: Long?,
    val requesterId: Long,
) : RuntimeException("User $requesterId is not allowed to modify board $boardId")
