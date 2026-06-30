package demo.hexagonal.hexagonalback

import demo.hexagonal.hexagonalback.support.PostgresTestContainer
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
class HexagonalBackApplicationTests {
    @Test
    fun contextLoads() {
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = PostgresTestContainer.registerProperties(registry)
    }
}
