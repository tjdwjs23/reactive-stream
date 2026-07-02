package demo.reactivestream.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

// 조회수 카운터용 Redis를 테스트에서 띄웁니다. 리액티브(Lettuce)로 접속하므로
// spring.data.redis.host/port만 주입하면 됩니다. 컨테이너는 JVM 당 싱글톤으로 공유합니다.
object RedisTestContainer {
    private const val REDIS_PORT = 6379

    private val container: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forListeningPort())
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.data.redis.host", container::getHost)
        registry.add("spring.data.redis.port") { container.getMappedPort(REDIS_PORT) }
    }
}
