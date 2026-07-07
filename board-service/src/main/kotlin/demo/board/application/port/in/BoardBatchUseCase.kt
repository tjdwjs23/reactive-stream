package demo.board.application.port.`in`

// 오래된 게시글을 일괄 아카이브(여기서는 삭제)하는 배치 유즈케이스입니다.
interface ArchiveStaleBoardsUseCase {
    suspend fun archiveStaleBoards(command: ArchiveStaleBoardsCommand): ArchiveResult
}

// 자가검증 커맨드(CreateBoardCommand와 동일한 컨벤션).
// chunkSize/concurrency를 커맨드로 노출해 런타임 튜닝이 가능합니다.
data class ArchiveStaleBoardsCommand(
    val retentionDays: Long,
    val chunkSize: Int = 500,
    val concurrency: Int = 4,
) {
    init {
        require(retentionDays >= 0) { "retentionDays는 0 이상이어야 합니다." }
        require(chunkSize in 1..10_000) { "chunkSize는 1에서 10000 사이여야 합니다." }
        require(concurrency in 1..64) { "concurrency는 1에서 64 사이여야 합니다." }
    }
}

// 배치 실행 결과 요약. scanned=훑은 건수, deleted=삭제 건수, failedChunks=건너뛴 청크 수.
data class ArchiveResult(
    val scanned: Int,
    val deleted: Int,
    val failedChunks: Int,
)
