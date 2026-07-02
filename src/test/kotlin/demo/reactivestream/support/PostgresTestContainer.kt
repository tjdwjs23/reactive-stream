package demo.reactivestream.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

object PostgresTestContainer {
    // PostgreSQLContainer의 기본 readiness 체크는 JDBC 커넥션으로 이뤄집니다.
    // JDBC 드라이버를 완전히 제거했으므로, 로그 메시지 기반 대기 전략으로 교체합니다.
    // postgres 이미지는 초기화 중 한 번, 실제 기동 시 한 번 "ready to accept connections"를 찍으므로 2회를 기다립니다.
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        // 애플리케이션 접근은 전 구간 R2DBC. 스키마는 ConnectionFactoryInitializer가 db/schema.sql로 초기화합니다.
        registry.add("spring.r2dbc.url") {
            "r2dbc:postgresql://${container.host}:${container.getMappedPort(5432)}/${container.databaseName}"
        }
        registry.add("spring.r2dbc.username", container::getUsername)
        registry.add("spring.r2dbc.password", container::getPassword)
    }
}
