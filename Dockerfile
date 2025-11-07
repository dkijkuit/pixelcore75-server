ARG JDK_VERSION=21
FROM gradle:8-jdk${JDK_VERSION} AS build

WORKDIR /workspace

# Copy Gradle wrapper & config first for better caching
COPY gradlew gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./

# Pre-fetch dependencies (speeds up subsequent builds)
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

# Now copy sources and build
COPY src src

# Try building a Spring Boot fat jar if present, otherwise a regular jar
RUN ./gradlew --no-daemon clean bootJar || ./gradlew --no-daemon clean build -x test

# Collect the built jar
RUN mkdir -p /out && \
    JAR_FILE=$(find build/libs -name "*.jar" | head -n 1) && \
    cp "$JAR_FILE" /out/app.jar

##
## Runtime stage
##
FROM eclipse-temurin:${JDK_VERSION}-jre

ENV JAVA_OPTS=""
# You can override this at runtime: -e SERVER_PORT=8080
ENV SERVER_PORT=8080

WORKDIR /app
COPY --from=build /out/app.jar /app/app.jar

EXPOSE 8080

# Simple health check against root (adjust if you have a dedicated health endpoint)
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD \
  wget -qO- http://localhost:${SERVER_PORT}/ || exit 1

# Use server.port if the app is Spring Boot; otherwise it's harmless
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${SERVER_PORT} -jar /app/app.jar"]
