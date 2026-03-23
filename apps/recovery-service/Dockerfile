FROM eclipse-temurin:17-jdk AS unpack

WORKDIR /workspace

COPY apps/recovery-service/build/distributions/recovery-service-0.1.0-SNAPSHOT.zip /workspace/recovery-service.zip

RUN mkdir -p /workspace/out \
    && cd /workspace/out \
    && jar -xf /workspace/recovery-service.zip \
    && mv /workspace/out/recovery-service-* /workspace/app

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=unpack /workspace/app/ /app/

RUN chmod +x /app/bin/recovery-service

EXPOSE 8080 9464

CMD ["/app/bin/recovery-service"]
