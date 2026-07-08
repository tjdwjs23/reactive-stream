package demo.board.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestContainer {
    // JPA(JDBC) 스택이라 PostgreSQLContainer의 기본 readiness(JDBC 커넥션 확인)를 그대로 씁니다.
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            // 이 컨테이너는 JVM당 싱글톤으로 공유됩니다. Spring TestContext가 서로 다른 @SpringBootTest 컨텍스트를
            // 동시에 캐시해 두므로, 각 컨텍스트의 HikariCP 풀이 이 한 대의 Postgres에 커넥션을 겹쳐 엽니다.
            // 기본 max_connections(100)로는 컨텍스트 수가 늘면 "too many clients already(53300)"로 터지므로 넉넉히 올립니다.
            .withCommand("postgres", "-c", "max_connections=400")
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        // 애플리케이션 접근은 JDBC(JPA/Hibernate) + Flyway. 스키마는 Flyway가 db/migration으로 초기화합니다.
        registry.add("spring.datasource.url", container::getJdbcUrl)
        registry.add("spring.datasource.username", container::getUsername)
        registry.add("spring.datasource.password", container::getPassword)
        registry.add("spring.datasource.driver-class-name", container::getDriverClassName)
    }
}
