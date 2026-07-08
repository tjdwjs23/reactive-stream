package demo.search.adapter.`in`.web

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

// 프레임워크가 던지는 ResponseStatusException(404/405/415 등)처럼 사전 정의되지 않은 상태 코드를
// 통일 포맷으로 감쌀 때 사용합니다. 미리 object로 나열할 수 없는 동적 상태 코드를 담습니다.
data class DynamicErrorCode(
    override val code: String,
    override val label: String,
    override val statusCode: Int,
) : ErrorCode

// 게시판 도메인 전용 에러 코드 모음.
object BoardErrorCode {
    object NotFound : ErrorCode {
        override val code = "BOARD_NOT_FOUND"
        override val label = "게시글을 찾을 수 없습니다."
        override val statusCode = HttpStatus.NOT_FOUND.value()
    }

    // 소유자도 관리자도 아닌 사용자가 남의 게시글을 수정/삭제하려 할 때(IDOR 차단).
    object AccessDenied : ErrorCode {
        override val code = "BOARD_ACCESS_DENIED"
        override val label = "해당 게시글에 대한 권한이 없습니다."
        override val statusCode = HttpStatus.FORBIDDEN.value()
    }
}

// 인증/인가 도메인 전용 에러 코드 모음.
object AuthErrorCode {
    object DuplicateUsername : ErrorCode {
        override val code = "DUPLICATE_USERNAME"
        override val label = "이미 사용 중인 사용자명입니다."
        override val statusCode = HttpStatus.CONFLICT.value()
    }

    object InvalidCredentials : ErrorCode {
        override val code = "INVALID_CREDENTIALS"
        override val label = "사용자명 또는 비밀번호가 올바르지 않습니다."
        override val statusCode = HttpStatus.UNAUTHORIZED.value()
    }

    // 짧은 시간에 로그인 실패가 임계치를 넘었을 때(brute-force 방어).
    object TooManyLoginAttempts : ErrorCode {
        override val code = "TOO_MANY_LOGIN_ATTEMPTS"
        override val label = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."
        override val statusCode = HttpStatus.TOO_MANY_REQUESTS.value()
    }

    // 리프레시 토큰이 무효/만료/재사용일 때(어느 쪽인지 구분하지 않음).
    object InvalidRefreshToken : ErrorCode {
        override val code = "INVALID_REFRESH_TOKEN"
        override val label = "리프레시 토큰이 유효하지 않습니다."
        override val statusCode = HttpStatus.UNAUTHORIZED.value()
    }
}
