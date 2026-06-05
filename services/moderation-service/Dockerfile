# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :services:moderation-service:bootJar --no-daemon

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 10001 parkio
COPY --from=build /workspace/services/moderation-service/build/libs/*.jar app.jar
USER parkio
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
