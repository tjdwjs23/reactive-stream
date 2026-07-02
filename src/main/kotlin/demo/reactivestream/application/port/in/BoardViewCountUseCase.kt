package demo.reactivestream.application.port.`in`

// Redis에 쌓인 조회수 델타를 DB로 write-back하는 유즈케이스입니다.
// 웹 요청이 아니라 스케줄러(구동 어댑터)가 주기적으로 구동합니다.
interface FlushBoardViewCountsUseCase {
    suspend fun flush(): FlushViewCountsResult
}

// 플러시 결과 요약. boards=반영 시도한 게시글 수, updatedRows=실제 UPDATE된 행 수, failed=실패 건수.
data class FlushViewCountsResult(
    val boards: Int,
    val updatedRows: Int,
    val failed: Int,
)
