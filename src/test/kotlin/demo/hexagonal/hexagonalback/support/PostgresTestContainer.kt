package demo.hexagonal.hexagonalback.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestContainer {
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        // 애플리케이션 런타임: R2DBC 접속 정보
        registry.add("spring.r2dbc.url") {
            "r2dbc:postgresql://${container.host}:${container.getMappedPort(5432)}/${container.databaseName}"
        }
        registry.add("spring.r2dbc.username", container::getUsername)
        registry.add("spring.r2dbc.password", container::getPassword)

        // Flyway 마이그레이션: JDBC 접속 정보(R2DBC 미지원)
        registry.add("spring.flyway.url", container::getJdbcUrl)
        registry.add("spring.flyway.user", container::getUsername)
        registry.add("spring.flyway.password", container::getPassword)
    }
}
