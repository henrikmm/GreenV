FROM gradle:9.7.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home-dir /app greenv \
    && mkdir -p /data/pipeline /data/saved \
    && chown -R greenv /data
COPY --from=build /workspace/build/libs/greenv-video-api-*.jar /app/service.jar
ENV GREENV_PIPELINE_ROOT=/data/pipeline \
    GREENV_SAVED_ROOT=/data/saved
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/service.jar"]
