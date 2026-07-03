package demo.board.adapter.`in`.batch

import org.springframework.boot.context.properties.ConfigurationProperties

// 조회수 write-back 플러시 튜닝 노브. 코드 변경 없이 운영에서 조절합니다.
@ConfigurationProperties(prefix = "board.view-count")
data class BoardViewCountProperties(
    val flushEnabled: Boolean = true,
    val flushIntervalMs: Long = 30_000,
)
