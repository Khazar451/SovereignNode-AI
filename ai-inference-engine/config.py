"""
config.py — SovereignNode AI Inference Engine Configuration
===========================================================
Single source of truth for all runtime settings.
Values are read from environment variables (set in docker-compose.yml)
with sensible production-safe defaults for every field.

Usage:
    from config import cfg
    print(cfg.LLM_MODEL_ID)
"""

from __future__ import annotations

import logging
import os
from pathlib import Path


def _env_bool(key: str, default: bool) -> bool:
    """Parse a truthy env var: '1', 'true', 'yes' → True."""
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
    All docker-compose.yml ``environment:`` keys are mirrored here.
    """

    # ── Paths ─────────────────────────────────────────────────────────────────
    CHROMA_PERSIST_DIR: Path = Path(
        os.getenv("CHROMA_PERSIST_DIR", "/app/chroma_store")
    )
    LLM_CACHE_DIR: Path = Path(
        os.getenv("LLM_CACHE_DIR", "/app/model_cache")
    )
    MANUALS_DIR: Path = Path(
        os.getenv("MANUALS_DIR", "/app/manuals")
    )

    # ── ChromaDB ──────────────────────────────────────────────────────────────
    CHROMA_COLLECTION_NAME: str = os.getenv(
        "CHROMA_COLLECTION_NAME", "sovnode_manuals"
    )

    # ── Embedding model ───────────────────────────────────────────────────────
    EMBEDDING_MODEL_NAME: str = os.getenv(
        "EMBEDDING_MODEL_NAME", "sentence-transformers/all-MiniLM-L6-v2"
    )
    EMBEDDING_DEVICE: str = os.getenv("EMBEDDING_DEVICE", "cuda")
    EMBEDDING_BATCH_SIZE: int = _env_int("EMBEDDING_BATCH_SIZE", 32)

    # ── LLM model ─────────────────────────────────────────────────────────────
    LLM_MODEL_ID: str = os.getenv(
        "LLM_MODEL_ID", "microsoft/Phi-3-mini-4k-instruct"
    )
    LLM_DEVICE_MAP: str = os.getenv("LLM_DEVICE_MAP", "auto")
    LLM_LOAD_IN_4BIT: bool = _env_bool("LLM_LOAD_IN_4BIT", True)
    LLM_BNB_COMPUTE_DTYPE: str = os.getenv("LLM_BNB_COMPUTE_DTYPE", "bfloat16")
    LLM_USE_FLASH_ATTENTION: bool = _env_bool("LLM_USE_FLASH_ATTENTION", True)

    # ── Generation hyperparameters ────────────────────────────────────────────
    MAX_NEW_TOKENS: int = _env_int("MAX_NEW_TOKENS", 512)
    TEMPERATURE: float = _env_float("TEMPERATURE", 0.1)
    TOP_P: float = _env_float("TOP_P", 0.9)
    REPETITION_PENALTY: float = _env_float("REPETITION_PENALTY", 1.1)

    # ── RAG retrieval ─────────────────────────────────────────────────────────
    TOP_K_RESULTS: int = _env_int("TOP_K_RESULTS", 3)
    SIMILARITY_THRESHOLD: float = _env_float("SIMILARITY_THRESHOLD", 0.3)

    # ── Document ingestion ────────────────────────────────────────────────────
    CHUNK_SIZE: int = _env_int("CHUNK_SIZE", 512)
    CHUNK_OVERLAP: int = _env_int("CHUNK_OVERLAP", 64)
    SUPPORTED_EXTENSIONS: tuple[str, ...] = (".txt", ".pdf", ".md")

    # ── Logging ───────────────────────────────────────────────────────────────
    LOG_LEVEL: int = getattr(
        logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO
    )


# Singleton — import and use everywhere as `from config import cfg`
cfg = _Config()
