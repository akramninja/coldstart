# syntax=docker/dockerfile:1

# Build: JDK 25 and Maven. Tests run with the local `mvnw clean package`; the image only packages.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/order-service-*.jar app.jar

# Run: JRE 25, non-root.
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar

# No heap flag, like most Dockerfiles: the JVM's default heap sizing is Default 1 of the article,
# so the image must not hide it. Scenarios add -XX:MaxRAMPercentage through JAVA_OPTS_APPEND, the
# way a pod spec would.
#
# ExitOnOutOfMemoryError: a JVM out of heap exits and gets restarted, like a pod, instead of
# limping on.
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"

# Numeric UID, so Kubernetes `runAsNonRoot` can verify it.
USER 10001:10001
EXPOSE 8080 9090

# `exec` makes the JVM PID 1: it receives SIGTERM directly and shuts down gracefully.
# JAVA_OPTS_APPEND is unquoted on purpose: it holds space-separated JVM flags.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS_APPEND:-} -jar /app/app.jar"]
