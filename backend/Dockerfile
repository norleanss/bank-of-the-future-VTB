# Сборка из корня репозитория: docker build -f backend/Dockerfile .
# (контракт API лежит в docs/api, он нужен для генерации кода)

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY docs/api/openapi.yaml docs/api/openapi.yaml
COPY backend/src backend/src
RUN --mount=type=cache,id=gamma-credit-maven,target=/root/.m2,sharing=locked \
    mvn -f backend/pom.xml -B -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 app
COPY --from=build /workspace/backend/target/credit-conveyor-backend-*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
