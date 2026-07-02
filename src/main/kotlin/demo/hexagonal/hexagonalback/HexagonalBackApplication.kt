package demo.hexagonal.hexagonalback

import demo.hexagonal.hexagonalback.adapter.`in`.batch.BoardArchivingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BoardArchivingProperties::class)
class HexagonalBackApplication

fun main(args: Array<String>) {
    runApplication<HexagonalBackApplication>(*args)
}
