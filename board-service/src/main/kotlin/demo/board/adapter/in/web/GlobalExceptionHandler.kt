package demo.board.adapter.`in`.web

import demo.board.domain.exception.BoardAccessDeniedException
import demo.board.domain.exception.BoardNotFoundException
import demo.board.domain.exception.BoardValidationException
import demo.board.domain.exception.DuplicateUsernameException
import demo.board.domain.exception.InvalidCredentialsException
import demo.board.domain.exception.InvalidRefreshTokenException
import demo.board.domain.exception.TooManyLoginAttemptsException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BoardNotFoundException::class)
    fun handleBoardNotFoundException(e: BoardNotFoundException): ResponseEntity<FailureResponse> =
        failure(BoardErrorCode.NotFound, e.message)

    // CreateBoardCommand.init 또는 Board.update()에서 발생하는 입력값 검증 실패
    @ExceptionHandler(BoardValidationException::class)
    fun handleBoardValidationException(e: BoardValidationException): ResponseEntity<FailureResponse> =
        failure(CommonErrorCode.ValidationError, e.message)

    // 소유자/관리자가 아닌 사용자의 수정·삭제 시도 → 403. 내부 식별자(요청자·게시글 id) 노출을 피하려 label만 응답합니다.
    @ExceptionHandler(BoardAccessDeniedException::class)
    fun handleBoardAccessDeniedException(): ResponseEntity<FailureResponse> = failure(BoardErrorCode.AccessDenied)

    // 가입 시 username 중복 → 409 Conflict
    @ExceptionHandler(DuplicateUsernameException::class)
    fun handleDuplicateUsernameException(e: DuplicateUsernameException): ResponseEntity<FailureResponse> =
        failure(AuthErrorCode.DuplicateUsername, e.message)

    // 로그인 실패(사용자 미존재/비밀번호 불일치) → 401 Unauthorized. 어느 쪽인지 구분하지 않습니다.
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentialsException(): ResponseEntity<FailureResponse> = failure(AuthErrorCode.InvalidCredentials)

    // 로그인 시도 과다(brute-force 방어) → 429 Too Many Requests.
    @ExceptionHandler(TooManyLoginAttemptsException::class)
    fun handleTooManyLoginAttemptsException(): ResponseEntity<FailureResponse> =
        failure(AuthErrorCode.TooManyLoginAttempts)

    // 리프레시 토큰 무효/만료/재사용 → 401 Unauthorized.
    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshTokenException(): ResponseEntity<FailureResponse> =
        failure(AuthErrorCode.InvalidRefreshToken)

    // 커맨드/쿼리의 자가 검증(init 블록 require())에서 발생하는 유효성 검사 실패
    // (CreateBoardCommand·SignUpCommand·BoardPageQuery·BoardSearchQuery·ArchiveStaleBoardsCommand 등)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<FailureResponse> =
        failure(CommonErrorCode.ValidationError, e.message)

    // Spring MVC의 입력 바인딩 실패를 400으로 매핑합니다.
    // - PathVariable/RequestParam 타입 불일치(예: GET /api/boards/abc) → MethodArgumentTypeMismatchException
    // - 필수 RequestParam 누락(예: 검색 keyword 누락) → MissingServletRequestParameterException
    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun handleParameterBindingException(): ResponseEntity<FailureResponse> = failure(CommonErrorCode.InvalidParameter)

    // 요청 Body 파싱 실패/누락(빈 Body, JSON 형식 오류, 타입 불일치) → 400.
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(): ResponseEntity<FailureResponse> =
        failure(CommonErrorCode.InvalidRequestBody)

    // 프레임워크가 던지는 상태 예외(존재하지 않는 경로의 NoResourceFoundException=404,
    // 405/415 등)는 그 HTTP 상태를 보존해 통일 포맷으로 응답합니다.
    // (아래 handleException(Exception)이 이를 500으로 뭉개지 않도록 더 구체적인 핸들러로 가로챕니다.)
    // ServerWebInputException(400)은 위의 전용 핸들러가 먼저 처리하므로 여기 오지 않습니다.
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<FailureResponse> {
        val status = HttpStatus.resolve(e.statusCode.value())
        val errorCode =
            DynamicErrorCode(
                code = status?.name ?: "HTTP_${e.statusCode.value()}",
                label = status?.reasonPhrase ?: "요청을 처리할 수 없습니다.",
                statusCode = e.statusCode.value(),
            )
        return failure(errorCode)
    }

    // 예상하지 못한 예외 - 내부 구현 정보 노출 방지를 위해 메시지를 고정값(label)으로 응답
    @ExceptionHandler(Exception::class)
    fun handleException(): ResponseEntity<FailureResponse> = failure(CommonErrorCode.InternalServerError)

    // 에러 코드의 statusCode를 HTTP 상태로, message(없으면 label)를 상세 메시지로 통일 응답합니다.
    private fun failure(
        errorCode: ErrorCode,
        message: String? = null,
    ): ResponseEntity<FailureResponse> {
        val body = FailureResponse.of(errorCode, message)
        return ResponseEntity.status(body.code).body(body)
    }
}
