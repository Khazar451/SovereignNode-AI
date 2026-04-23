"""
embedder.py — HuggingFace Sentence-Transformer Embedding Wrapper
================================================================
Provides a singleton EmbeddingModel that encodes text into dense vectors
for both document ingestion and query-time retrieval.

Design choices:
  - Singleton pattern: the heavy model is loaded once and reused.
  - CUDA-first with automatic CPU fallback.
  - normalize_embeddings=True produces unit-sphere vectors, making
    dot-product == cosine similarity (ChromaDB default metric).
"""

from __future__ import annotations

import logging
from functools import lru_cache
from typing import List, Union

import torch
from sentence_transformers import SentenceTransformer

from config import cfg

logger = logging.getLogger(__name__)


class EmbeddingModel:
    """
    Thread-safe wrapper around a SentenceTransformer.

    Parameters
    ----------
    model_name : str
        HuggingFace model ID or local path.
    device : str
        Preferred device ('cuda', 'cpu'). Falls back to CPU automatically.
    batch_size : int
        Chunk batch size for encoding. Tune to fill available VRAM.
    """

    def __init__(
        self,
        model_name: str = cfg.EMBEDDING_MODEL_NAME,
        device: str = cfg.EMBEDDING_DEVICE,
        batch_size: int = cfg.EMBEDDING_BATCH_SIZE,
    ) -> None:
        # ── Resolve device ────────────────────────────────────────────────
        if device == "cuda" and not torch.cuda.is_available():
            logger.warning(
                "CUDA requested but not available — falling back to CPU. "
                "Embedding throughput will be significantly lower."
            )
            device = "cpu"

        self.device = device
        self.batch_size = batch_size

        logger.info("Loading embedding model '%s' on %s …", model_name, device)
        self._model = SentenceTransformer(
            model_name,
            device=device,
            cache_folder=str(cfg.LLM_CACHE_DIR),
        )
        self._model.eval()  # Inference mode: disables dropout
        logger.info(
            "Embedding model ready — dim=%d", self._model.get_sentence_embedding_dimension()
        )

    # ── Public API ────────────────────────────────────────────────────────

    def encode(
        self,
        texts: Union[str, List[str]],
        show_progress: bool = False,
    ) -> List[List[float]]:
        """
        Encode one or more texts into normalised dense vectors.

        Parameters
        ----------
        texts : str | list[str]
            The text(s) to encode.
        show_progress : bool
            Show a tqdm progress bar during batch encoding.

        Returns
        -------
        list[list[float]]
            A list of embedding vectors (one per input text).
            Vectors are L2-normalised (unit sphere).
        """
        if isinstance(texts, str):
            texts = [texts]

        embeddings = self._model.encode(
            texts,
            batch_size=self.batch_size,
            show_progress_bar=show_progress,
            normalize_embeddings=True,   # cosine sim ≡ dot product
            convert_to_numpy=True,
        )
        return embeddings.tolist()

    def encode_query(self, query: str) -> List[float]:
        """
        Convenience method for encoding a single query string.
        Returns a flat list[float] (not a list-of-lists).
        """
        return self.encode([query])[0]

    @property
    def dimension(self) -> int:
        """Embedding vector dimensionality."""
        return self._model.get_sentence_embedding_dimension()


@lru_cache(maxsize=1)
def get_embedding_model() -> EmbeddingModel:
    """
    Return the process-level singleton EmbeddingModel.
    Subsequent calls return the cached instance without re-loading.
    """
    return EmbeddingModel()
