# 백엔드 서비스 이미지. 서비스 이름은 docker-compose.yml의 build.args(SERVICE)로 받는다.
# 빌드 단계에는 SERVICE가 없어서 모든 서비스가 같은 빌드 결과를 나눠 쓴다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon --console=plain bootJar

FROM eclipse-temurin:25-jre
ARG SERVICE
RUN useradd --system --no-create-home anthill
COPY --from=build /workspace/services/${SERVICE}/build/libs/${SERVICE}-*.jar /app/app.jar
USER anthill
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
