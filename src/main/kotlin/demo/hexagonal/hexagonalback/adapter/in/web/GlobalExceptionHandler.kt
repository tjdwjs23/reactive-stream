package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.exception.BoardValidationException
import org.springframework.beans.TypeMismatchException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BoardNotFoundException::class)
    fun handleBoardNotFoundException(e: BoardNotFoundException): ResponseEntity<FailureResponse> =
        failure(BoardErrorCode.NotFound, e.message)

    // CreateBoardCommand.init 또는 Board.update()에서 발생하는 입력값 검증 실패
    @ExceptionHandler(BoardValidationException::class)
    fun handleBoardValidationException(e: BoardValidationException): ResponseEntity<FailureResponse> =
        failure(CommonErrorCode.ValidationError, e.message)

    // CreateBoardCommand.init의 require()에서 발생하는 자가 검증 실패
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<FailureResponse> =
        failure(CommonErrorCode.ValidationError, e.message)

    // WebFlux에서 입력 바인딩 실패는 대부분 ServerWebInputException(400)으로 수렴합니다.
    // - PathVariable/파라미터 타입 불일치(예: GET /api/boards/abc): cause가 TypeMismatchException
    // - 요청 Body 파싱 실패/누락(빈 Body, 타입 불일치): 그 외
    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInputException(e: ServerWebInputException): ResponseEntity<FailureResponse> =
        if (e.cause is TypeMismatchException) {
            failure(CommonErrorCode.InvalidParameter)
        } else {
            failure(CommonErrorCode.InvalidRequestBody)
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
