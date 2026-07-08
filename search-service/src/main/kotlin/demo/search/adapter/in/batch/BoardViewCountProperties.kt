package demo.search.adapter.`in`.batch

import org.springframework.boot.context.properties.ConfigurationProperties

// 조회수 write-back 플러시 튜닝 노브. 코드 변경 없이 운영에서 조절합니다.
// (플러시 주기 flush-interval-ms는 @Scheduled의 fixedDelayString SpEL이 직접 읽으므로 여기 필드로 두지 않습니다.)
@ConfigurationProperties(prefix = "board.view-count")
data class BoardViewCountProperties(
    val flushEnabled: Boolean = true,
)
