# Build from the repository root: docker build -f backend/Dockerfile .
# Build stage: compile and package the Spring Boot jar plus the shared SDK registry.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
COPY frontend/shared/richtext/service.json /frontend/shared/richtext/service.json
RUN mvn -B -ntp -DskipTests package

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 quizforge
WORKDIR /app
COPY --from=build /src/target/quizforge-backend.jar app.jar
USER quizforge
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
