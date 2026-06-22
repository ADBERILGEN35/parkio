# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew :services:media-service:bootJar --no-daemon

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 10001 parkio
COPY --from=build /workspace/services/media-service/build/libs/*.jar app.jar
USER parkio
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
