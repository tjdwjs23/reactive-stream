package demo.hexagonal.hexagonalback.adapter.`in`.web

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorResponse? = null,
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)

        fun fail(
            code: String,
            message: String,
        ): ApiResponse<Nothing> = ApiResponse(success = false, error = ErrorResponse(code = code, message = message))
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
)
