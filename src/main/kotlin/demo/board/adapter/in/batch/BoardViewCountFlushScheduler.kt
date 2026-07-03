package demo.board.adapter.`in`.batch

import demo.board.application.port.`in`.FlushBoardViewCountsUseCase
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// Driving Adapter: 주기적으로 조회수 플러시 유즈케이스를 구동합니다.
// 웹 컨트롤러/아카이브 스케줄러와 마찬가지로 유즈케이스 인터페이스에만 의존합니다.
@Component
class BoardViewCountFlushScheduler(
    private val flushUseCase: FlushBoardViewCountsUseCase,
    private val properties: BoardViewCountProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 고정 지연 주기로 실행합니다. @Scheduled는 suspend를 직접 호출할 수 없으므로
    // 구동 어댑터에서 runBlocking으로 코루틴 세계에 연결합니다(블로킹 경계를 어댑터 안에 가둠).
    @Scheduled(fixedDelayString = "\${board.view-count.flush-interval-ms:30000}")
    fun run() {
        if (!properties.flushEnabled) return
        val result = runBlocking { flushUseCase.flush() }
        if (result.boards > 0) log.info("Board view count flush: {}", result)
    }
}
