package demo.search.adapter.`in`.batch

import demo.search.application.port.`in`.ArchiveStaleBoardsCommand
import demo.search.application.port.`in`.ArchiveStaleBoardsUseCase
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// Driving Adapter: 스케줄이 유즈케이스를 "구동"하는 입력 어댑터입니다.
// 웹 컨트롤러와 마찬가지로 유즈케이스 인터페이스에만 의존하고, 구체 서비스는 모릅니다.
@Component
class StaleBoardArchivingScheduler(
    private val archiveStaleBoardsUseCase: ArchiveStaleBoardsUseCase,
    private val properties: BoardArchivingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 프로퍼티 미설정 시에도 컨텍스트가 뜨도록 기본 cron을 둡니다(테스트/로컬 안전장치).
    @Scheduled(cron = "\${search.archiving.cron:0 0 4 * * *}")
    fun run() {
        if (!properties.enabled) {
            log.debug("Stale board archiving is disabled. Skip.")
            return
        }

        val command =
            ArchiveStaleBoardsCommand(
                retentionDays = properties.retentionDays,
                chunkSize = properties.chunkSize,
                concurrency = properties.concurrency,
            )

        log.info("Stale board archiving started: {}", command)
        // @Scheduled는 suspend 함수를 직접 호출할 수 없으므로, 구동 어댑터에서 스케줄러 스레드를
        // runBlocking으로 코루틴 세계에 연결합니다(블로킹 경계를 어댑터 안으로 가둠).
        val result = runBlocking { archiveStaleBoardsUseCase.archiveStaleBoards(command) }
        log.info("Stale board archiving done: {}", result)
    }
}
