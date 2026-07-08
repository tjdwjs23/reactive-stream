package demo.search

import demo.search.adapter.`in`.batch.BoardArchivingProperties
import demo.search.adapter.`in`.batch.BoardViewCountProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BoardArchivingProperties::class, BoardViewCountProperties::class)
class SearchApplication

fun main(args: Array<String>) {
    runApplication<SearchApplication>(*args)
}
