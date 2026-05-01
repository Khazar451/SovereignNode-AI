# ─────────────────────────────────────────────────────────────────────────────
# SovereignNode AI — Python Inference Engine Dockerfile
# Base: NVIDIA CUDA 12.1 runtime on Ubuntu 22.04 (supports NF4 / bitsandbytes)
# ─────────────────────────────────────────────────────────────────────────────

# Stage 1: dependency builder
FROM nvidia/cuda:12.1.0-runtime-ubuntu22.04 AS builder

# Prevent interactive prompts during apt installs
ENV DEBIAN_FRONTEND=noninteractive

# System dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3.11 \
        python3.11-dev \
        python3.11-venv \
        python3-pip \
        build-essential \
        curl \
        git \
    && rm -rf /var/lib/apt/lists/*

# Use python3.11 as the default python
RUN update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.11 1 \
    && update-alternatives --install /usr/bin/pip pip /usr/bin/pip3 1

WORKDIR /build

# Install PyTorch with CUDA 12.1 support first (large wheel, separate layer for cache)
RUN pip install --no-cache-dir \
    torch==2.3.0 \
    --index-url https://download.pytorch.org/whl/cu121

# Install remaining dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: final runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM nvidia/cuda:12.1.0-runtime-ubuntu22.04 AS runtime

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    # HuggingFace: disable tokenizer parallelism warnings in single-worker mode
    TOKENIZERS_PARALLELISM=false \
    # Point HF to the shared model cache volume
    HF_HOME=/app/model_cache \
    TRANSFORMERS_CACHE=/app/model_cache

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3.11 \
        python3-pip \
        libgomp1 \
        curl \
    && rm -rf /var/lib/apt/lists/*

RUN update-alternatives --install /usr/bin/python3 python3 /usr/bin/python3.11 1

# Copy installed packages from builder stage
COPY --from=builder /usr/local/lib/python3.11/dist-packages /usr/local/lib/python3.11/dist-packages
COPY --from=builder /usr/local/bin /usr/local/bin

# Application source
WORKDIR /app
COPY . .

# Directories that will be mounted as volumes at runtime
RUN mkdir -p /app/model_cache /app/chroma_store /app/manuals

# Non-root user for security
RUN groupadd -g 1001 sovnode && useradd -u 1001 -g sovnode -m sovnode
RUN chown -R sovnode:sovnode /app
USER sovnode

# Health check: curl with python3 urllib fallback (belt-and-suspenders)
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=120s \
    CMD curl -sf http://localhost:8000/health || \
        python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

EXPOSE 8000

# Single uvicorn worker: the LLM singleton is not process-safe for fork().
# Scale horizontally by running multiple containers behind a load balancer.
ENTRYPOINT ["python3", "-m", "uvicorn", "api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
