package demo.board.support

import org.springframework.test.context.DynamicPropertyRegistry

// 통합 테스트가 필요로 하는 컨테이너(Postgres + Redis + Elasticsearch) 접속 정보를 한 번에 등록합니다.
// 앱 컨텍스트에는 JDBC(JPA)·Redis·ES 클라이언트가 모두 포함되므로(예: Actuator health가 셋 다 확인),
// 전체 컨텍스트 테스트는 세 컨테이너를 함께 띄워 둡니다. 컨테이너는 각 support 오브젝트의 싱글톤입니다.
object TestContainers {
    fun registerAll(registry: DynamicPropertyRegistry) {
        PostgresTestContainer.registerProperties(registry)
        RedisTestContainer.registerProperties(registry)
        ElasticsearchTestContainer.registerProperties(registry)
    }
}
