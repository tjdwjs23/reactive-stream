package demo.hexagonal.hexagonalback.adapter.`in`.web

import org.springframework.http.HttpStatus

// 모든 에러 코드가 구현하는 공통 계약입니다.
// - code: 클라이언트/로그가 분기에 쓰는 머신용 식별자 (예: "BOARD_NOT_FOUND")
// - label: 별도 메시지가 없을 때 쓰는 사람용 기본 메시지
// - statusCode: HTTP 상태 값 (예: 404)
interface ErrorCode {
    val code: String
    val label: String
    val statusCode: Int
}

// 여러 도메인에서 공통으로 쓰는 에러 코드 모음.
object CommonErrorCode {
    object ValidationError : ErrorCode {
        override val code = "VALIDATION_ERROR"
        override val label = "잘못된 요청입니다."
        override val statusCode = HttpStatus.BAD_REQUEST.value()
    }

    object InvalidRequestBody : ErrorCode {
        override val code = "INVALID_REQUEST_BODY"
        override val label = "요청 본문을 읽을 수 없습니다."
        override val statusCode = HttpStatus.BAD_REQUEST.value()
    }

    object InvalidParameter : ErrorCode {
        override val code = "INVALID_PARAMETER"
        override val label = "파라미터 형식이 올바르지 않습니다."
        override val statusCode = HttpStatus.BAD_REQUEST.value()
    }

    object InternalServerError : ErrorCode {
        override val code = "INTERNAL_SERVER_ERROR"
        override val label = "서버 내부 오류가 발생했습니다."
        override val statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value()
    }
}

// 게시판 도메인 전용 에러 코드 모음.
object BoardErrorCode {
    object NotFound : ErrorCode {
        override val code = "BOARD_NOT_FOUND"
        override val label = "게시글을 찾을 수 없습니다."
        override val statusCode = HttpStatus.NOT_FOUND.value()
    }
}
