package demo.board.adapter.`in`.batch

import org.springframework.boot.context.properties.ConfigurationProperties

// 배치 실행 파라미터를 외부 설정(application.yml)으로 뺍니다.
// 코드 변경 없이 운영 중 처리량을 조절할 수 있게 합니다.
@ConfigurationProperties(prefix = "board.archiving")
data class BoardArchivingProperties(
    val enabled: Boolean = false,
    val cron: String = "0 0 4 * * *",
    val retentionDays: Long = 365,
    val chunkSize: Int = 500,
    val concurrency: Int = 4,
)
