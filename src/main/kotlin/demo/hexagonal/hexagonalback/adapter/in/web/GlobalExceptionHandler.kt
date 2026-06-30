package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.exception.BoardValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BoardNotFoundException::class)
    fun handleBoardNotFoundException(e: BoardNotFoundException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("BOARD_NOT_FOUND", e.message!!))

    // CreateBoardCommand.init 또는 Board.update()에서 발생하는 입력값 검증 실패
    @ExceptionHandler(BoardValidationException::class)
    fun handleBoardValidationException(e: BoardValidationException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("VALIDATION_ERROR", e.message!!))

    // CreateBoardCommand.init의 require()에서 발생하는 자가 검증 실패
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.fail("VALIDATION_ERROR", e.message ?: "Invalid request"))

    // 요청 Body가 JSON으로 파싱되지 않는 경우 (필수 필드 누락, 타입 불일치 등)
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.fail("INVALID_REQUEST_BODY", "요청 본문을 읽을 수 없습니다."))

    // PathVariable 타입 불일치 (예: GET /api/boards/abc)
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(
        e: MethodArgumentTypeMismatchException,
    ): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.fail("INVALID_PARAMETER", "'${e.name}' 파라미터 형식이 올바르지 않습니다."))

    // 예상하지 못한 예외 - 내부 구현 정보 노출 방지를 위해 메시지를 고정값으로 응답
    @ExceptionHandler(Exception::class)
    fun handleException(): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."))
}
