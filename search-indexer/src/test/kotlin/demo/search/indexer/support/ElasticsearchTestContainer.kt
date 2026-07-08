package demo.search.indexer.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.elasticsearch.ElasticsearchContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration

// 색인 검증용 Elasticsearch(+Nori)를 테스트에서 띄웁니다. search-service와 동일한 Nori Dockerfile을 빌드하며,
// 같은 이미지 태그를 써서 docker 레이어 캐시를 공유합니다(최초 1회 이후 빠름). 컨테이너는 JVM당 싱글톤.
object ElasticsearchTestContainer {
    private val imageName: String =
        ImageFromDockerfile("search-elasticsearch-nori-test", false)
            .withDockerfile(Path.of("docker/elasticsearch/Dockerfile"))
            .get()

    private val container: ElasticsearchContainer =
        ElasticsearchContainer(
            DockerImageName
                .parse(imageName)
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"),
        ).withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(3))
            .apply { start() }

    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.elasticsearch.uris") { "http://${container.httpHostAddress}" }
    }
}
