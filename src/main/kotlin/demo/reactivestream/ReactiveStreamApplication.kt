package demo.reactivestream

import demo.reactivestream.adapter.`in`.batch.BoardArchivingProperties
import demo.reactivestream.adapter.`in`.batch.BoardViewCountProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BoardArchivingProperties::class, BoardViewCountProperties::class)
class ReactiveStreamApplication

fun main(args: Array<String>) {
    runApplication<ReactiveStreamApplication>(*args)
}
