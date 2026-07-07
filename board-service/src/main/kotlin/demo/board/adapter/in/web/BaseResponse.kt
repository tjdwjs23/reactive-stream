package demo.board.adapter.`in`.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.net.URI

// 성공/실패를 아우르는 공통 응답 계약. 모든 API 응답은 { code, status, result } 형태로 통일됩니다.
interface BaseResponse<T> {
    val code: Int
    val status: String
    val result: T?
}

// 성공 응답. 컨트롤러는 아래 팩토리로 ResponseEntity를 만들어 상태 코드/헤더까지 함께 통일합니다.
data class SuccessResponse<T>(
    override val code: Int,
    override val status: String = "Success",
    override val result: T?,
) : BaseResponse<T> {
    companion object {
        fun <T> ok(res: T): ResponseEntity<SuccessResponse<T>> =
            ResponseEntity.ok(SuccessResponse(code = HttpStatus.OK.value(), result = res))

        // 생성된 리소스를 그대로 반환합니다(201 + Location 헤더 + result=생성 리소스).
        fun <T> created(
            res: T,
            location: URI,
        ): ResponseEntity<SuccessResponse<T>> =
            ResponseEntity
                .created(location)
                .body(SuccessResponse(code = HttpStatus.CREATED.value(), result = res))

        // 204 No Content는 HTTP 규격상 본문을 갖지 않습니다. 통일 봉투(BaseResponse) 대신
        // 본문 없는 순수 204를 반환합니다(본문이 필요 없는 삭제 응답).
        fun noContent(): ResponseEntity<Void> = ResponseEntity.noContent().build()
    }
}

// 실패 응답. result에는 에러 정의(code/label/statusCode)가, message에는 상황별 상세 메시지가 담깁니다.
// message가 없으면 에러 코드의 기본 메시지(label)로 채웁니다.
// error를 private으로 두어 직렬화에서 제외하고, 노출은 result 하나로만 통일합니다.
data class FailureResponse(
    private val error: ErrorCode,
    val message: String,
) : BaseResponse<ErrorCode> {
    override val code = error.statusCode
    override val status = "Failure"
    override val result = error

    companion object {
        fun of(
            error: ErrorCode,
            message: String? = null,
        ): FailureResponse = FailureResponse(error, message ?: error.label)
    }
}
