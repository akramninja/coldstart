# syntax=docker/dockerfile:1

# Build: JDK 25 and Maven. Tests run with the local `mvnw test`; the image only packages.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/orders-api-*.jar app.jar

# Run: JRE 25, non-root.
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar

# Article 1, Default 1: size the heap from the container limit, not 25% of it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

USER 10001:10001
EXPOSE 8080 9090

# exec: the JVM is PID 1 and receives signals directly.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS_APPEND:-} -jar /app/app.jar"]
