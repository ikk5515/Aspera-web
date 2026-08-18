FROM eclipse-temurin:22-jdk-jammy@sha256:d8e6ba486df17bf758888d2b1b608133d1eedca8daf69d3fc6bf78d8be81e07e AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew gradlew
COPY build.gradle settings.gradle gradle.lockfile ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew --no-daemon clean test bootJar

FROM eclipse-temurin:22-jre-jammy@sha256:dbcae8b5dd4d63f81739a538ec2c09797735f04a21d814f9071b62f018326043
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build --chown=app:app /app/build/libs/*.jar /app/app.jar

USER app
ENV JAVA_TOOL_OPTIONS=""
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
