# The RunPod serverless image: the DA3 service, plus the handler that feeds it from a bucket.
#
# Built FROM the image `measurement/server/Dockerfile` produces, not from a copy of its steps.
# That image is ~12 GB of CUDA, xformers, Depth Anything 3 and a 6.7 GB checkpoint pinned to one
# revision, and duplicating those pins here is how two records start disagreeing about which
# model is deployed. So:
#
#   docker build -t verge-da3:local measurement/server
#   docker build -t <registry>/greenv-depth-runpod:<tag> \
#          --build-arg DEPTH_IMAGE=verge-da3:local services/greenv-depth-runpod
#
# The base carries /app/{main.py,contract.py,vram.py}; this layer adds /app/handler.py beside
# them and changes the entry point from "serve HTTP" to "drain a RunPod queue". Nothing in
# `measurement/` is edited, and the subtree stays round-trippable.
ARG DEPTH_IMAGE=verge-da3:local
FROM ${DEPTH_IMAGE}

# runpod is the serverless SDK, boto3 the S3-compatible client, httpx the loopback client that
# speaks to the DA3 service. See requirements.txt for why these are ranges rather than pins.
COPY requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir --requirement /tmp/requirements.txt && rm /tmp/requirements.txt

WORKDIR /app
COPY handler.py /app/handler.py

# Fail the build, not the first job. A base image that is not the DA3 one, a missing uvicorn or a
# handler that does not import all cost a deploy and a cold start to discover otherwise, and this
# project has twice paid for code that was written but never executed.
RUN test -f /app/main.py && test -f /app/contract.py && test -f /app/vram.py \
    && command -v uvicorn >/dev/null \
    && python -c "import boto3, httpx, runpod, handler; print('handler import check OK')"

# The handler publishes to the S3-compatible bucket itself, so the DA3 service must keep its
# artifacts on local disk and serve them from /artifact. Setting VERGE_OUTPUT_BUCKET here would
# send them to GCS instead, which this deployment has no credentials for; the handler unsets it
# defensively as well.
ENV VERGE_OUTPUT_BUCKET=""
# A run leaves ~130 MB behind and the handler has already copied it to the bucket by the time the
# job answers, so the service's own sweep can be much tighter than the six hours a browser
# session wants. It runs at the top of the next /infer.
ENV VERGE_RUN_TTL_SECONDS=3600
ENV GREENV_DEPTH_SERVICE_PORT=8080 \
    GREENV_DEPTH_SERVICE_DIR=/app \
    PYTHONUNBUFFERED=1

CMD ["python", "-u", "/app/handler.py"]
