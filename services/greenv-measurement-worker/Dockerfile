# Built from the REPOSITORY ROOT, not from this directory.
#
#   docker build -f services/greenv-measurement-worker/Dockerfile .
#
# The other services build from their own directory; this one cannot, because it has to carry
# `measurement/` — the process it spawns lives there, and the boundary is a process call rather
# than an import precisely so the two halves can be built and versioned like this.

FROM node:22-bookworm-slim

WORKDIR /app

# ffmpeg is NOT needed here: sampling already happened in the frame extractor, and this stage
# reads JPEGs. What is needed is a toolchain for the native ONNX and sharp binaries.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 greenv \
    && useradd -r -u 10001 -g greenv greenv

# Verge Studio first, and its dependencies resolved through app/package.json — which is how
# `scripts/assess-grass.mjs` resolves sharp and the transformers runtime at run time.
COPY measurement/app/package.json measurement/app/package-lock.json /app/measurement/app/
RUN npm ci --omit=dev --prefix /app/measurement/app

COPY measurement/scripts /app/measurement/scripts
COPY measurement/geometry /app/measurement/geometry
COPY measurement/package.json /app/measurement/package.json

# The pinned segmentation model, ~15 MB, baked in. This is the ONE network call in the whole
# build: the inspector refuses to download at run time by design, so a container without a warm
# cache fails every frame with an instruction instead of quietly fetching weights in production.
ENV VERGE_MODEL_CACHE=/app/measurement/.models
RUN node /app/measurement/scripts/fetch-model.mjs

COPY services/greenv-measurement-worker/package.json services/greenv-measurement-worker/package-lock.json /app/worker/
RUN npm ci --omit=dev --prefix /app/worker
COPY services/greenv-measurement-worker/src /app/worker/src

RUN mkdir -p /data/pipeline /data/runs && chown -R greenv /data
ENV GREENV_PIPELINE_ROOT=/data/pipeline \
    VERGE_RUNS_ROOT=/data/runs \
    GREENV_MEASUREMENT_CLI=/app/measurement/scripts/assess-grass.mjs \
    SERVER_ADDRESS=0.0.0.0 \
    PORT=8090

USER 10001
EXPOSE 8090
ENTRYPOINT ["node", "/app/worker/src/main.mjs"]
