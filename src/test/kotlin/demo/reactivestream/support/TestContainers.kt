package demo.reactivestream.support

import org.springframework.test.context.DynamicPropertyRegistry

// 통합 테스트가 필요로 하는 컨테이너(Postgres + Redis) 접속 정보를 한 번에 등록합니다.
// 앱 컨텍스트에는 R2DBC와 리액티브 Redis가 모두 포함되므로(예: Actuator health가 둘 다 확인),
// 전체 컨텍스트 테스트는 두 컨테이너를 함께 띄워 둡니다. 컨테이너는 각 support 오브젝트의 싱글톤입니다.
object TestContainers {
    fun registerAll(registry: DynamicPropertyRegistry) {
        PostgresTestContainer.registerProperties(registry)
        RedisTestContainer.registerProperties(registry)
    }
}
