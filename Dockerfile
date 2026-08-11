FROM eclipse-temurin:17-jdk-jammy@sha256:29467857e8bde40ab1f7befecbda0ea764b95afec1cc7f89aa90f7a766577e19 AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew gradlew
COPY build.gradle settings.gradle gradle.lockfile ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew --no-daemon clean test bootJar

FROM eclipse-temurin:17-jre-jammy@sha256:89e68b9bb83713510b63e2059a415792a7fc77e14b739a7d7ede97f6d9ca2c38
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build --chown=app:app /app/build/libs/*.jar /app/app.jar

USER app
ENV JAVA_TOOL_OPTIONS=""
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
