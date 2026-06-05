# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :services:ai-validation-service:bootJar --no-daemon

# ---- runtime stage ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 10001 parkio
COPY --from=build /workspace/services/ai-validation-service/build/libs/*.jar app.jar
USER parkio
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
