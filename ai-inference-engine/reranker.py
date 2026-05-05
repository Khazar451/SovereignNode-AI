"""
reranker.py — Cross-Encoder Re-ranking for RAG Confidence ≥90%
===============================================================
Implements the "Retrieve-and-Rerank" pattern:

  1. Bi-encoder (ChromaDB cosine) retrieves top-N candidates fast.
  2. Cross-encoder scores each (query, chunk) pair with high precision.
  3. Re-rank by cross-encoder score, keep top-K.

Why cross-encoders score higher:
  - Bi-encoder: encodes query and document independently → cosine ~80-86%
  - Cross-encoder: encodes (query, document) jointly → relevance score 90-99%
    for well-matched pairs, using full attention over both texts.

Model: cross-encoder/ms-marco-MiniLM-L-6-v2
  - 22M parameters, fast on CPU (~5ms per pair)
  - Trained on 530k MS-MARCO passage pairs (question → passage relevance)
  - Output: raw logit, convert to [0,1] via sigmoid
  - Typical scores: highly relevant ≥ 0.90, tangentially related 0.5-0.8
"""

from __future__ import annotations

import logging
import math
from functools import lru_cache
from typing import List, Tuple

from sentence_transformers import CrossEncoder
from config import cfg

logger = logging.getLogger(__name__)


class CrossEncoderReranker:
    """
    Thin wrapper around a SentenceTransformers CrossEncoder model.

    Scores (query, passage) pairs and returns probabilities in [0, 1].
    The sigmoid transform converts raw logits to interpretable confidence scores.
    """

    def __init__(self, model_name: str = cfg.RERANKER_MODEL) -> None:
        logger.info("Loading cross-encoder reranker '%s' …", model_name)
        self._model = CrossEncoder(
            model_name,
            max_length=512,
            device=cfg.RERANKER_DEVICE,
        )
        logger.info("Cross-encoder reranker ready.")


    def score(self, query: str, passages: List[str]) -> List[float]:
        """
        Score each (query, passage) pair.

        Parameters
        ----------
        query : str
            The retrieval query.
        passages : list[str]
            The candidate passages to score.

        Returns
        -------
        list[float]
            Relevance scores in [0, 1] for each passage (sigmoid-normalised).
            Score ≥ 0.90 indicates strong relevance.
        """
        if not passages:
            return []

        pairs = [(query, p) for p in passages]
        raw_scores: List[float] = self._model.predict(pairs).tolist()

        # Sigmoid normalisation: maps (-inf, +inf) logits → (0, 1)
        return [_sigmoid(s) for s in raw_scores]

    def rerank(
        self,
        query: str,
        items: List[Tuple[str, object]],   # (passage_text, original_object)
        top_k: int,
    ) -> List[Tuple[float, object]]:
        """
        Re-rank items by cross-encoder relevance score.

        Parameters
        ----------
        query : str
            The retrieval query.
        items : list of (passage_text, any)
            Candidate passages with their associated metadata objects.
        top_k : int
            Number of top results to return.

        Returns
        -------
        list of (score, original_object) sorted by descending score.
        """
        if not items:
            return []

        passages = [text for text, _ in items]
        scores   = self.score(query, passages)

        ranked = sorted(
            zip(scores, [obj for _, obj in items]),
            key=lambda x: x[0],
            reverse=True,
        )
        return ranked[:top_k]


def _sigmoid(x: float) -> float:
    """Numerically stable sigmoid: maps logit → probability in (0, 1)."""
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


@lru_cache(maxsize=1)
def get_reranker() -> CrossEncoderReranker:
    """
    Return the process-level singleton CrossEncoderReranker.
    Loaded once on first call, cached for all subsequent calls.
    """
    return CrossEncoderReranker()
