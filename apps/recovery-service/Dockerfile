FROM gradle:8.10.2-jdk17 AS build

WORKDIR /workspace

COPY build.gradle.kts settings.gradle.kts ./
COPY apps/recovery-service apps/recovery-service

RUN gradle :apps:recovery-service:installDist --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /workspace/apps/recovery-service/build/install/recovery-service /app

EXPOSE 8080 9464

CMD ["/app/bin/recovery-service"]

