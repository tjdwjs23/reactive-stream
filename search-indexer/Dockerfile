# search-indexer 이미지. 빌드 컨텍스트는 **모노레포 루트**여야 합니다(event-contract 의존 + settings.gradle의 모듈 존재 필요).
#   docker build -f search-indexer/Dockerfile -t search-indexer:local .
#
# ── build stage ──
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY event-contract ./event-contract
COPY board-service ./board-service
COPY search-indexer ./search-indexer
RUN chmod +x gradlew && ./gradlew --no-daemon :search-indexer:bootJar -x test

# ── runtime stage ──
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /workspace/search-indexer/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
