package demo.board.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration

// 한글 전문검색용 Elasticsearch(+Nori)를 테스트에서 띄웁니다.
// 공식 이미지에는 Nori가 없으므로 docker/elasticsearch/Dockerfile을 빌드해 플러그인이 포함된
// 이미지를 만든 뒤 실행합니다(로컬 docker-compose와 동일 구성). 컨테이너는 JVM 당 싱글톤으로 공유합니다.
// 앱 컨텍스트에 ES 클라이언트와 헬스가 포함되므로 전체 컨텍스트 테스트에도 실제 ES가 필요합니다.
object ElasticsearchTestContainer {
    // Dockerfile 빌드는 docker 레이어 캐시가 적용되어(플러그인 설치 레이어) 최초 1회 이후엔 빠릅니다.
    private val imageName: String =
        ImageFromDockerfile("board-elasticsearch-nori-test", false)
            .withDockerfile(Path.of("docker/elasticsearch/Dockerfile"))
            .get()

    private val container: ElasticsearchContainer =
        ElasticsearchContainer(
            DockerImageName
                .parse(imageName)
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"),
        ).withEnv("discovery.type", "single-node")
            // 로컬/CI 편의를 위해 보안 비활성화(http, 인증 없음) — application.yml의 접속 방식과 동일
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(3))
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.elasticsearch.uris") { "http://${container.httpHostAddress}" }
    }
}
