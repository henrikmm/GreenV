FROM gradle:9.7.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache ffmpeg \
    && addgroup -S -g 10001 greenv \
    && adduser -S -D -H -u 10001 -G greenv greenv \
    && mkdir -p /data/pipeline \
    && chown -R greenv /data
COPY --from=build /workspace/build/libs/greenv-frame-extractor-*.jar /app/service.jar
ENV GREENV_PIPELINE_ROOT=/data/pipeline
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/service.jar"]
