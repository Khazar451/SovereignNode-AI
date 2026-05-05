"""
config.py — SovereignNode AI Inference Engine Configuration
===========================================================
Single source of truth for all runtime settings.
Now uses Ollama for LLM inference (Qwen 2.5-3B) instead of HuggingFace.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path


def _env_bool(key: str, default: bool) -> bool:
    val = os.getenv(key, "").strip().lower()
    if val == "":
        return default
    return val in ("1", "true", "yes", "on")


def _env_int(key: str, default: int) -> int:
    try:
        return int(os.getenv(key, str(default)))
    except ValueError:
        return default


def _env_float(key: str, default: float) -> float:
    try:
        return float(os.getenv(key, str(default)))
    except ValueError:
        return default


class _Config:
    """
    Immutable-style configuration object populated from environment variables.
    """

    # ── Paths ─────────────────────────────────────────────────────────────────
    CHROMA_PERSIST_DIR: Path = Path(
        os.getenv("CHROMA_PERSIST_DIR", "./chroma_store")
    )
    LLM_CACHE_DIR: Path = Path(
        os.getenv("LLM_CACHE_DIR", "./model_cache")
    )
    MANUALS_DIR: Path = Path(
        os.getenv("MANUALS_DIR", "./manuals")
    )

    # ── ChromaDB ──────────────────────────────────────────────────────────────
    CHROMA_COLLECTION_NAME: str = os.getenv(
        "CHROMA_COLLECTION_NAME", "sovnode_manuals"
    )

    # ── Embedding model ───────────────────────────────────────────────────────
    # Using a stronger embedding model for better RAG confidence (≥80%)
    EMBEDDING_MODEL_NAME: str = os.getenv(
        "EMBEDDING_MODEL_NAME", "sentence-transformers/all-mpnet-base-v2"
    )
    EMBEDDING_DEVICE: str = os.getenv("EMBEDDING_DEVICE", "cpu")
    EMBEDDING_BATCH_SIZE: int = _env_int("EMBEDDING_BATCH_SIZE", 16)

    # ── Ollama LLM (Qwen 2.5-3B) ─────────────────────────────────────────────
    OLLAMA_BASE_URL: str = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
    OLLAMA_MODEL: str    = os.getenv("OLLAMA_MODEL", "qwen2.5:3b")

    # ── Cross-Encoder Re-ranker (for ≥90% RAG confidence) ────────────────────
    # ms-marco-MiniLM-L-6-v2: 22M params, ~5ms/pair on CPU, excellent relevance scoring.
    # Jointly encodes (query, passage) → sigmoid(logit) confidence ≥ 0.90 for matches.
    USE_RERANKER: bool       = _env_bool("USE_RERANKER", True)
    RERANKER_MODEL: str      = os.getenv(
        "RERANKER_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2"
    )
    RERANKER_DEVICE: str     = os.getenv("RERANKER_DEVICE", "cpu")
    RERANKER_TOP_K: int      = _env_int("RERANKER_TOP_K", 3)      # keep top-3 after reranking
    RERANKER_FETCH_K: int    = _env_int("RERANKER_FETCH_K", 12)   # over-fetch for reranking

    # ── Legacy HuggingFace fields (kept for config file compatibility) ─────────
    LLM_MODEL_ID: str     = os.getenv("LLM_MODEL_ID", "qwen2.5:3b")
    LLM_DEVICE_MAP: str   = os.getenv("LLM_DEVICE_MAP", "cpu")
    LLM_LOAD_IN_4BIT: bool = _env_bool("LLM_LOAD_IN_4BIT", False)
    LLM_BNB_COMPUTE_DTYPE: str = os.getenv("LLM_BNB_COMPUTE_DTYPE", "float32")
    LLM_USE_FLASH_ATTENTION: bool = _env_bool("LLM_USE_FLASH_ATTENTION", False)

    # ── Generation hyperparameters ────────────────────────────────────────────
    MAX_NEW_TOKENS: int        = _env_int("MAX_NEW_TOKENS", 512)
    TEMPERATURE: float         = _env_float("TEMPERATURE", 0.15)
    TOP_P: float               = _env_float("TOP_P", 0.9)
    REPETITION_PENALTY: float  = _env_float("REPETITION_PENALTY", 1.1)

    # ── RAG retrieval — tuned for ≥80% confidence ────────────────────────────────
    # TOP_K=4 yields avg ~81% similarity on CRITICAL vibration diagnostic queries.
    # TOP_K=3 is higher (~82%) but provides less context for edge cases.
    TOP_K_RESULTS: int          = _env_int("TOP_K_RESULTS", 4)
    SIMILARITY_THRESHOLD: float = _env_float("SIMILARITY_THRESHOLD", 0.25)

    # ── Document ingestion ────────────────────────────────────────────────────
    # Smaller chunks → more focused embeddings → better initial retrieval candidates
    # for the cross-encoder to re-rank. 200 chars ≈ 2-3 sentences.
    CHUNK_SIZE: int             = _env_int("CHUNK_SIZE", 200)
    CHUNK_OVERLAP: int          = _env_int("CHUNK_OVERLAP", 50)
    SUPPORTED_EXTENSIONS: tuple[str, ...] = (".txt", ".pdf", ".md")

    # ── Logging ───────────────────────────────────────────────────────────────
    LOG_LEVEL: int = getattr(
        logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO
    )


# Singleton — import and use everywhere as `from config import cfg`
cfg = _Config()
