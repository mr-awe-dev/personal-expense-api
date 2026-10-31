FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .

# dependency:go-offline removed – causes snapshot resolution failure in CI mirrors.
# The subsequent mvn package step resolves correctly (as verified locally).
COPY src ./src
RUN mvn -B -ntp -DskipTests package


FROM eclipse-temurin:21-jre-noble AS runtime

RUN apt-get update \
 && apt-get install --no-install-recommends -y \
    curl \
    tesseract-ocr \
    tesseract-ocr-ind \
    tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 pet \
 && useradd --system --uid 1001 --gid pet --create-home --home-dir /app pet

WORKDIR /app
COPY --from=build /workspace/target/personal-expense-api-*.jar /app/app.jar

USER pet
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
