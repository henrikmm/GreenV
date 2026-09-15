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
# `scripts/assess-grass.mjs` resolves sharp, the transformers runtime and esbuild at run time.
#
# `--omit=dev` is why esbuild has to be declared in app/package.json rather than arriving through
# vite: `scripts/inspect/typed.mjs` requires it on every measurement, and a dependency that is
# only present because some devDependency happens to pull it in is absent from this image.
#
# scripts/ is copied BEFORE the install, not after: app/package.json declares
# `"preinstall": "node ../scripts/check-node.mjs"`, so npm ci fails outright if that file is not
# already in place. There is no package.json at the root of measurement/ — app/ carries the only
# manifest — so nothing is copied for one.
COPY measurement/scripts /app/measurement/scripts
COPY measurement/app/package.json measurement/app/package-lock.json /app/measurement/app/
RUN npm ci --omit=dev --prefix /app/measurement/app

# The pinned segmentation models baked in: the Cityscapes B0 that decides what is grass (~15 MB)
# and the ADE20K B4 (~246 MB) or the Mapillary MaskFormer (~158 MB) that say what is not — Cityscapes never taught the first one a
# guardrail. This is the ONE network call in the whole build: the inspector refuses to download
# at run time by design, so a container without a warm cache fails every frame with an
# instruction instead of quietly fetching weights in production.
ENV VERGE_MODEL_CACHE=/app/measurement/.models
RUN node /app/measurement/scripts/fetch-model.mjs cityscapes-b0 ade20k-b4 vistas-r50

# The two TypeScript trees `scripts/inspect/bridge.ts` re-exports from. The bridge is compiled at
# RUN time, not at build time — `typed.mjs` bundles it on every measurement so the inspector can
# never disagree with the geometry it inspects — which means a source file missing here fails the
# measurement instead of the build. app/src is on the list because the bridge takes npz parsing,
# the colour map, the up-axis rule and the road-edge grid from the app.
#
# Copied after the model fetch so that editing either tree does not spend this build's one
# network call again.
COPY measurement/geometry /app/measurement/geometry
COPY measurement/app/src /app/measurement/app/src

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
