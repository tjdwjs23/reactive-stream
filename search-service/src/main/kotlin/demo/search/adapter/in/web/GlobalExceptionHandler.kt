package demo.search.adapter.`in`.web

import demo.search.domain.exception.BoardAccessDeniedException
import demo.search.domain.exception.BoardNotFoundException
import demo.search.domain.exception.BoardValidationException
import demo.search.domain.exception.DuplicateUsernameException
import demo.search.domain.exception.InvalidCredentialsException
import demo.search.domain.exception.InvalidRefreshTokenException
import demo.search.domain.exception.ProductNotFoundException
import demo.search.domain.exception.TooManyLoginAttemptsException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BoardNotFoundException::class)
    fun handleBoardNotFoundException(e: BoardNotFoundException): ResponseEntity<FailureResponse> =
        failure(BoardErrorCode.NotFound, e.message)

    // 없는 상품 조회/삭제 → 404. 상품 전용 코드/메시지로 응답합니다(게시글과 구분).
    @ExceptionHandler(ProductNotFoundException::class)
    fun handleProductNotFoundException(e: ProductNotFoundException): ResponseEntity<FailureResponse> =
        failure(
            DynamicErrorCode(
                code = "PRODUCT_NOT_FOUND",
                label = "상품을 찾을 수 없습니다.",
                statusCode = HttpStatus.NOT_FOUND.value(),
            ),
            e.message,
        )

    // Board.update()에서 발생하는 입력값 검증 실패(생성 시의 require() 실패는 아래 IllegalArgumentException 핸들러가 담당)
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

    // 매핑되지 않은 경로 → 404. 실서버(정적 리소스 핸들러)에선 NoResourceFoundException(ResponseStatusException 하위)이,
    // 핸들러 매핑이 없는 구성(일부 MockMvc)에선 NoHandlerFoundException이 던져지므로, 후자를 명시적으로 404로 매핑합니다
    // (전자는 아래 handleResponseStatusException이 처리). 둘 다 통일 실패 포맷으로 응답합니다.
    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNoHandlerFound(): ResponseEntity<FailureResponse> =
        failure(
            DynamicErrorCode(
                code = "NOT_FOUND",
                label = "요청한 리소스를 찾을 수 없습니다.",
                statusCode = HttpStatus.NOT_FOUND.value(),
            ),
        )

    // 프레임워크가 던지는 상태 예외(존재하지 않는 경로의 NoResourceFoundException=404,
    // 405/415 등)는 그 HTTP 상태를 보존해 통일 포맷으로 응답합니다.
    // (아래 handleException(Exception)이 이를 500으로 뭉개지 않도록 더 구체적인 핸들러로 가로챕니다.)
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

    // 예상하지 못한 예외 - 내부 구현 정보 노출 방지를 위해 메시지는 고정값(label)으로 응답하되, 원인은 서버 로그에 남깁니다
    // (조용한 500을 방지 — 운영에서 5xx 원인 추적 가능).
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<FailureResponse> {
        log.error("unhandled exception mapped to 500", e)
        return failure(CommonErrorCode.InternalServerError)
    }

    // 에러 코드의 statusCode를 HTTP 상태로, message(없으면 label)를 상세 메시지로 통일 응답합니다.
    private fun failure(
        errorCode: ErrorCode,
        message: String? = null,
    ): ResponseEntity<FailureResponse> {
        val body = FailureResponse.of(errorCode, message)
        return ResponseEntity.status(body.code).body(body)
    }
}
