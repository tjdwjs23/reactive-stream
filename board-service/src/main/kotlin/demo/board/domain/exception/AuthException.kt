package demo.board.domain.exception

// 이미 존재하는 username으로 가입을 시도했을 때. 웹 계층에서 409 Conflict로 매핑됩니다.
class DuplicateUsernameException(
    username: String,
) : RuntimeException("Username already exists: $username")

// 로그인 시 사용자 미존재 또는 비밀번호 불일치. 어느 쪽인지 구분하지 않아(사용자 존재 여부 노출 방지)
// 단일 예외로 통일하며, 웹 계층에서 401 Unauthorized로 매핑됩니다.
class InvalidCredentialsException : RuntimeException("Invalid username or password")
