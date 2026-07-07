# board-service 이미지. 빌드 컨텍스트는 **모노레포 루트**여야 합니다(event-contract 의존 + settings.gradle의 모듈 존재 필요).
#   docker build -f board-service/Dockerfile -t board-service:local .
#
# ── build stage: Gradle로 board-service bootJar 생성 ──
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# 빌드 설정 먼저 복사(의존성 해석 레이어 캐시)
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
# settings.gradle.kts가 세 모듈을 include하므로 디렉터리가 모두 존재해야 configuration이 통과합니다.
COPY event-contract ./event-contract
COPY board-service ./board-service
COPY search-indexer ./search-indexer
RUN chmod +x gradlew && ./gradlew --no-daemon :board-service:bootJar -x test

# ── runtime stage: JRE만 담은 경량 런타임 ──
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# 비루트 사용자로 실행
RUN groupadd --system app && useradd --system --gid app --home /app app
# bootJar만 생성했으므로 build/libs에는 실행 가능한 fat jar 하나뿐입니다(-plain.jar 없음).
COPY --from=build /workspace/board-service/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
