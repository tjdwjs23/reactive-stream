package demo.search.adapter.`in`.batch

import org.springframework.boot.context.properties.ConfigurationProperties

// 배치 실행 파라미터를 외부 설정(application.yml)으로 뺍니다.
// 코드 변경 없이 운영 중 처리량을 조절할 수 있게 합니다.
// (실행 주기 cron은 @Scheduled의 SpEL이 직접 읽으므로 여기 필드로 두지 않습니다.)
@ConfigurationProperties(prefix = "search.archiving")
data class BoardArchivingProperties(
    val enabled: Boolean = false,
    val retentionDays: Long = 365,
    val chunkSize: Int = 500,
    val concurrency: Int = 4,
)
