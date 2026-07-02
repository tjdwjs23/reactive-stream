package demo.hexagonal.hexagonalback.adapter.`in`.web

import demo.hexagonal.hexagonalback.domain.exception.BoardNotFoundException
import demo.hexagonal.hexagonalback.domain.exception.BoardValidationException
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

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

    // WebFlux에서 입력 바인딩 실패는 대부분 ServerWebInputException(400)으로 수렴합니다.
    // - PathVariable/파라미터 타입 불일치(예: GET /api/boards/abc): cause가 TypeMismatchException
    // - 요청 Body 파싱 실패/누락(빈 Body, 타입 불일치): 그 외
    // 원인을 구분해 기존과 동일한 에러 코드(INVALID_PARAMETER / INVALID_REQUEST_BODY)로 응답합니다.
    @ExceptionHandler(ServerWebInputException::class)
    fun handleServerWebInputException(e: ServerWebInputException): ResponseEntity<ApiResponse<Nothing>> =
        if (e.cause is TypeMismatchException) {
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("INVALID_PARAMETER", "파라미터 형식이 올바르지 않습니다."))
        } else {
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("INVALID_REQUEST_BODY", "요청 본문을 읽을 수 없습니다."))
        }

    // 예상하지 못한 예외 - 내부 구현 정보 노출 방지를 위해 메시지를 고정값으로 응답
    @ExceptionHandler(Exception::class)
    fun handleException(): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.fail("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."))
}
