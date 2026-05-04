---
title: AI Inference Engine
tags: [sovereignnode, python, rag, llm, inference, fastapi]
created: 2026-05-04
updated: 2026-05-04
type: engineering-note
related: ["[[IoT Telemetry Service]]", "[[SovNode Dashboard]]", "[[SovereignNode AI — Index]]"]
---

# 🧠 AI Inference Engine

> [!NOTE] What Is This?
> The **ai-inference-engine** is the Python-based AI brain of SovereignNode. It is a fully offline, air-gapped **Retrieval-Augmented Generation (RAG)** system that answers industrial diagnostic questions by reading from enterprise maintenance manuals — no cloud API calls, no data egress, everything runs locally on the edge device.

**Location:** `SovereignNode-AI/ai-inference-engine/`
**Language:** Python 3.11
**Framework:** FastAPI + Uvicorn
**Exposes:** `POST /generate-insight`, `GET /health` on port `8000`
**Called by:** [[IoT Telemetry Service]] (on vibration anomaly), [[SovNode Dashboard]] (directly on anomaly detection)

---

## File Map

| File | Role |
|---|---|
| `config.py` | Single source of truth — all settings from env vars |
| `embedder.py` | HuggingFace sentence-transformer wrapper (singleton) |
| `ingest.py` | Document ingestion pipeline: PDF/TXT → chunks → ChromaDB |
| `rag_engine.py` | Full RAG pipeline: query → retrieve → prompt → generate |
| `inference.py` | 4-bit quantized LLM loader and text generator |
| `api.py` | FastAPI REST bridge to the Java service |
| `main.py` | CLI interface: `ingest`, `query`, `chat` subcommands |
| `test_api.py` | Unit tests with mocked `query_rag` |
| `Dockerfile` | Multi-stage CUDA 12.1 container |
| `requirements.txt` | Pinned Python dependencies |
| `manuals/` | Directory where enterprise PDF/TXT documents are placed |

---

## How It Works — The Full RAG Pipeline

The pipeline has two phases: **offline ingestion** (run once) and **online querying** (run on demand via the API).

### Phase 1 — Document Ingestion (`ingest.py`)

Run before the service goes live. Loads enterprise maintenance manuals into the vector store.

**Steps:**

1. **Discover** — recursively finds `.txt`, `.pdf`, `.md` files in `./manuals/`
2. **Extract** — PDFs via `pypdf` (no poppler dependency); TXT files via `chardet` auto-encoding detection
3. **Chunk** — sliding window of **512 characters** with **64-character overlap** to avoid losing context at chunk boundaries
4. **Embed** — each chunk is encoded into a dense vector by the sentence transformer model
5. **Upsert** — written to ChromaDB in batches of 100; chunk IDs are **deterministic SHA-256 hashes** of `(file_path + chunk_index)` so re-running is idempotent

> [!TIP] Idempotent Ingestion
> Because chunk IDs are derived from a hash of the file path + index, you can safely re-run `python main.py ingest` or use `--reset` to wipe and re-index. The same document will always produce the same IDs.

**CLI usage:**

```bash
python main.py ingest --dir ./manuals        # ingest all manuals
python main.py ingest --dir ./manuals --reset  # wipe first, then ingest
```

---

### Phase 2 — Query Pipeline (`rag_engine.py`)

Executed on every call to `POST /generate-insight` or `python main.py query`.

**Step 1 — Embed the query**

The user's natural language question is encoded into a dense vector using the same sentence-transformer model used during ingestion. This ensures semantic compatibility.

**Step 2 — Retrieve from ChromaDB**

The query vector is compared against all stored chunk vectors using **cosine similarity** (HNSW index). The top-K most similar chunks are returned (default K=3). Chunks below the `SIMILARITY_THRESHOLD` (default 0.3) are discarded.

**Step 3 — Build the grounded prompt**

Retrieved passages are injected into a structured prompt using **ChatML format** (designed for Phi-3 / Qwen instruction-tuned models):

```
<|im_start|>system
You are SovereignNode AI, a precise factual assistant for industrial
maintenance engineers.

STRICT RULES:
1. Answer ONLY using information from the CONTEXT passages below.
2. If the context lacks enough information, say exactly:
   'I do not have sufficient information in the provided documentation
    to answer this question.'
3. Never fabricate facts, specifications, or procedures.
4. Always cite the source filename(s) at the end of your answer.
5. Be concise, technically accurate, and professionally written.
<|im_end|>
<|im_start|>user
CONTEXT:
[Passage 1] (Source: pump_manual.pdf, similarity: 0.842)
... extracted text ...

QUESTION:
What is the maintenance interval for the pump seal?
<|im_end|>
<|im_start|>assistant
```

**Step 4 — Generate via local LLM**

The prompt is passed to the `LanguageModel.generate()` method in `inference.py`. The model runs locally with `torch.inference_mode()` and returns only the newly generated tokens (the prompt is excluded from the decoded output).

**Step 5 — Return `RAGResult`**

```python
@dataclass
class RAGResult:
    query: str
    answer: str
    retrieved_chunks: List[RetrievedChunk]  # includes source file + similarity score
    prompt: str                              # full prompt kept for auditability
```

---

## `inference.py` — Quantized LLM

> [!IMPORTANT] Model Loading Strategy
> The model is loaded **once per process** using `@lru_cache(maxsize=1)`. All subsequent calls reuse the same singleton. This is critical — loading a quantized LLM takes 30–120 seconds depending on hardware.

**Quantization configuration (NF4 4-bit):**

| Setting | Value | Reason |
|---|---|---|
| `bnb_4bit_quant_type` | `nf4` | Normal Float 4 — best quality among 4-bit types |
| `bnb_4bit_compute_dtype` | `bfloat16` | Stable numerics on Ampere+ GPUs (RTX 30/40 series) |
| `bnb_4bit_use_double_quant` | `True` | Also quantizes the quantization constants, saves ~0.4 GB VRAM |
| Attention | `flash_attention_2` | 4–6× faster on long contexts if CUDA available |

**Generation parameters:**

| Parameter | Default | Effect |
|---|---|---|
| `temperature` | `0.1` | Near-deterministic — factual, reproducible answers |
| `top_p` | `0.9` | Nucleus sampling threshold |
| `repetition_penalty` | `1.1` | Prevents the model from looping on phrases |
| `max_new_tokens` | `512` | Upper bound on answer length |

**CPU fallback:** If CUDA is unavailable, 4-bit mode is automatically disabled and the model loads in full-precision on CPU. Inference will be slow but functional.

---

## `embedder.py` — Sentence Transformer

Model: `sentence-transformers/all-MiniLM-L6-v2`

- Loaded once as a singleton via `@lru_cache`
- `normalize_embeddings=True` places all vectors on the unit sphere — this makes **dot product = cosine similarity**, matching ChromaDB's default metric
- CUDA-first with automatic CPU fallback
- Two public methods: `encode(list[str])` for batch ingestion, `encode_query(str)` for single query at retrieval time

---

## `api.py` — FastAPI REST Bridge

Wraps the entire RAG pipeline as an HTTP service so the [[IoT Telemetry Service]] can call it over the internal Docker network.

### Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/generate-insight` | POST | Full RAG pipeline — returns AI diagnostic insight |
| `/health` | GET | Liveness check — returns CUDA info + model config |
| `/docs` | GET | Auto-generated Swagger UI |

### Request / Response Contract

**Request (`InsightRequest`):**

```json
{
  "sensor_id": "PUMP-001",
  "anomaly_type": "HIGH_VIBRATION",
  "raw_telemetry": {
    "temperature": 65.0,
    "vibration": 9.5,
    "timestamp": "2026-05-04T17:00:00.000Z"
  },
  "query": "What is the root cause of high vibration in this pump?"
}
```

**Response (`InsightResponse`):**

```json
{
  "insight": "Based on Section 4.3 of pump_manual.pdf, vibration above 9 mm/s...",
  "confidence_score": 0.847,
  "sources_referenced": ["pump_manual.pdf"],
  "inference_time_ms": 4231.5
}
```

**Query enrichment:** Before calling `query_rag`, the API appends telemetry context to the user query:

```
<original query> [Context: sensor=PUMP-001, anomaly_type=HIGH_VIBRATION, temperature=65.0C, vibration=9.5mm/s, timestamp=...]
```

This ensures the retrieved passages and generated answer are grounded in both the enterprise documentation AND the specific sensor readings at anomaly time.

> [!WARNING] Thread Safety
> The LLM singleton is not thread-safe. A `threading.Lock()` (`_inference_lock`) serializes all concurrent requests to the `/generate-insight` endpoint. The Dockerfile enforces `--workers 1` on Uvicorn. Scale horizontally with multiple containers behind a load balancer.

### Confidence Score

Derived from the average cosine similarity of retrieved chunks:

```python
avg_similarity = sum(chunk.similarity_score for chunk in chunks) / len(chunks)
confidence_score = round(min(1.0, max(0.0, avg_similarity)), 4)
```

A low confidence score means the manuals didn't contain closely matching content. The answer should be treated with scepticism.

---

## `config.py` — Configuration

All settings are read from environment variables set in `docker-compose.yml`, with safe defaults:

| Setting | Default | Notes |
|---|---|---|
| `LLM_MODEL_ID` | `microsoft/Phi-3-mini-4k-instruct` | Any HuggingFace causal-LM |
| `EMBEDDING_MODEL_NAME` | `sentence-transformers/all-MiniLM-L6-v2` | Any sentence-transformer |
| `LLM_LOAD_IN_4BIT` | `True` | Requires CUDA; falls back to FP32 on CPU |
| `LLM_BNB_COMPUTE_DTYPE` | `bfloat16` | Use `float16` on older GPUs |
| `LLM_USE_FLASH_ATTENTION` | `True` | Requires Ampere+ GPU |
| `CHUNK_SIZE` | `512` chars | Document chunk window |
| `CHUNK_OVERLAP` | `64` chars | Overlap between adjacent chunks |
| `TOP_K_RESULTS` | `3` | Chunks retrieved per query |
| `SIMILARITY_THRESHOLD` | `0.3` | Minimum cosine similarity to keep a chunk |
| `TEMPERATURE` | `0.1` | Generation temperature |
| `MAX_NEW_TOKENS` | `512` | Max output tokens |
| `CHROMA_PERSIST_DIR` | `/app/chroma_store` | Where ChromaDB stores its index |
| `MANUALS_DIR` | `/app/manuals` | Where source documents are mounted |

---

## `main.py` — Developer CLI

A rich terminal interface for operating the RAG system without going through HTTP:

```bash
# Load all documents from ./manuals into ChromaDB
python main.py ingest --dir ./manuals

# Ask a single question and get an answer with source citations
python main.py query "What is the maintenance interval for the pump seal?"

# Start an interactive REPL session
python main.py chat --stream
```

Uses the `rich` library for panels, tables, markdown rendering, and spinners. The `chat` subcommand reads stdin in a loop and calls `query_rag` on each input. `--stream` enables live token streaming to stdout.

---

## Dockerfile — Multi-Stage CUDA Container

**Stage 1 (builder):** Installs Python 3.11, PyTorch with CUDA 12.1, and all pip dependencies. Build tools are kept isolated.

**Stage 2 (runtime):** Minimal CUDA 12.1 Ubuntu 22.04 image. Copies only the installed packages from the builder (not the build tools). Runs as non-root user `sovnode` (UID 1001) for security.

**Health check:** Polls `GET /health` every 30 seconds with a 120-second startup grace period (model loading takes time).

**Entry point:**
```
uvicorn api:app --host 0.0.0.0 --port 8000 --workers 1
```

---

## Dependencies (Key)

| Package | Version | Purpose |
|---|---|---|
| `fastapi` | 0.111.0 | REST API framework |
| `uvicorn[standard]` | 0.30.0 | ASGI server |
| `transformers` | 4.44.2 | HuggingFace model loading (pinned — 5.x has a known bug) |
| `bitsandbytes` | 0.43.3 | 4-bit NF4 quantization |
| `accelerate` | 0.33.0 | Device mapping + model dispatch |
| `sentence-transformers` | 3.0.1 | Embedding model |
| `chromadb` | 0.5.0 | Vector store |
| `pypdf` | 4.2.0 | PDF text extraction (no poppler needed) |
| `chardet` | 5.2.0 | TXT encoding auto-detection |
| `rich` | 13.7.0 | Beautiful terminal output for CLI |

> [!CAUTION] Pinned transformers Version
> `transformers==4.44.2` is intentionally pinned. Version 5.x has a `NameError` in `integrations/accelerate.py` (`nn` used before import). Do not upgrade without testing.

---

## Related Notes

- [[IoT Telemetry Service]] — calls this service via `POST /generate-insight` on anomaly detection
- [[SovNode Dashboard]] — calls this service directly via Vite proxy on anomaly detection
- [[SovereignNode AI — Index]] — platform overview

---

#sovereignnode #python #rag #llm #fastapi #chromadb #inference #edge-ai
