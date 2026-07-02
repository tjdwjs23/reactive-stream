package demo.hexagonal.hexagonalback.adapter.out.persistence

import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator

// Flyway(JDBC) 대신 R2DBC 커넥션으로 스키마를 초기화합니다.
// 애플리케이션 기동 시 ConnectionFactoryInitializer가 db/schema.sql을 논블로킹으로 실행합니다.
// schema.sql은 CREATE TABLE IF NOT EXISTS라 여러 번 실행돼도 안전합니다(테스트 컨텍스트 재기동 포함).
@Configuration
class R2dbcSchemaInitializer {
    @Bean
    fun connectionFactoryInitializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer =
        ConnectionFactoryInitializer().apply {
            setConnectionFactory(connectionFactory)
            setDatabasePopulator(ResourceDatabasePopulator(ClassPathResource("db/schema.sql")))
        }
}
