# PyTorch 2.8 / CUDA 12.8 rather than 2.5.1 / 12.1, because 12.8 is the first CUDA with
# Blackwell kernels and the RTX PRO 6000 is a Blackwell card. The same image serves the L4, so
# switching cards is a redeploy, never a rebuild. Changed 2026-10-17 for the FIAP Next demo; the
# L4 must reproduce a graded fixture on this image before the 2.5.1 image is deleted.
FROM pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime

ARG DEBIAN_FRONTEND=noninteractive
# DA3 imports cv2 at module load (utils/camera_trj_helpers.py), so opencv's shared
# libraries must be present even though we never decode video here. The donor image got
# these transitively from ffmpeg; we install just the libs instead of the decoder.
#   libglib2.0-0 -> libgthread-2.0.so.0
#   libgl1       -> libGL.so.1
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
         git ca-certificates \
         libglib2.0-0 libgl1 libsm6 libxext6 libxrender1 \
    && rm -rf /var/lib/apt/lists/*

# No ffmpeg and no yt-dlp: frame extraction is local-first (the Mac does it) and the
# cloud never resolves or downloads video.
#
# DA3 is installed without its dependency list: it asks for an unpinned xformers, which would
# pull a torch of its own choosing over the one this image is built on. Without xformers DA3's
# attention runs on PyTorch's own SDPA, which has Blackwell kernels; the 2.5.1 image used
# xformers 0.0.28, so this is the one numerical change in the rebuild and the L4 regression
# run is what checks it. The package list is the one the live image proved on 2026-09-25
# (`feat/measurement-live-reconstruction`, live/Dockerfile), plus what this server uses.
#
# huggingface_hub is held below 1.0. On 2026-10-22 an unpinned build drew 2.2.0, whose httpx2
# transport calls a zstd decoder API that the backports.zstd evo pulls in does not have, and the
# checkpoint download died with "process() takes no keyword arguments". The 0.x line is the one
# DA3 was written against and the one every image before that date carried.
RUN python -m pip install --no-cache-dir --no-deps \
      "git+https://github.com/ByteDance-Seed/Depth-Anything-3.git@3d835ec1a5802d64a8b8b15f817a1ab54809bfe4" \
    && python -m pip install --no-cache-dir \
      "numpy<2" einops "huggingface_hub<1.0" safetensors omegaconf imageio opencv-python-headless \
      trimesh plyfile pillow_heif e3nn evo typer moviepy==1.0.3 requests addict pycolmap \
      google-cloud-storage==2.19.0 \
      "fastapi==0.115.6" \
      "uvicorn[standard]==0.34.0" \
      "python-multipart==0.0.20"

# Fail the build, not the first request. DA3 pulls cv2 in transitively at import time,
# so a missing system library otherwise only surfaces after a deploy and a cold start.
# Runs before the 6.7 GB checkpoint download so failures are cheap.
RUN python -c "import torch, cv2, fastapi, uvicorn; from depth_anything_3.api import DepthAnything3; print('import check OK', torch.__version__, torch.version.cuda)"

COPY download-models.py /tmp/download-models.py
RUN python /tmp/download-models.py && rm /tmp/download-models.py

WORKDIR /app
COPY contract.py vram.py preview.py main.py /app/

ENV PYTHONUNBUFFERED=1
ENV HF_HUB_OFFLINE=1
ENV TRANSFORMERS_OFFLINE=1
ENV PORT=8080

# Single worker: one L4, one model in VRAM. Concurrency for the live-telemetry
# endpoints comes from the event loop, not from extra workers.
CMD exec uvicorn main:app --host 0.0.0.0 --port ${PORT} --workers 1
