package demo.hexagonal.hexagonalback.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestContainer {
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withInitScript("sql/board.sql")
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", container::getJdbcUrl)
        registry.add("spring.datasource.username", container::getUsername)
        registry.add("spring.datasource.password", container::getPassword)
    }
}
