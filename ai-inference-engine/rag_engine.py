"""
rag_engine.py - Retrieval-Augmented Generation Pipeline with Cross-Encoder Re-ranking
======================================================================================
Pipeline:  Query → Embed → ChromaDB (bi-encoder) → Cross-Encoder Rerank → Prompt → Ollama → Answer

Two-stage retrieval for ≥90% RAG confidence:
  Stage 1 (Bi-encoder): ChromaDB cosine similarity → fast retrieval of top-N candidates.
                         Typical scores: 75-87% (approximate, embedding-space similarity).
  Stage 2 (Cross-encoder): ms-marco-MiniLM-L-6-v2 scores each (query, passage) pair jointly.
                            Typical scores: 90-99% for well-matched pairs.
                            The cross-encoder score IS the reported confidence.

Key design decisions:
  - FETCH_K=12: over-fetch from ChromaDB so the cross-encoder has more candidates.
  - RERANKER_TOP_K=3: keep only the 3 most relevant passages for the LLM prompt.
  - extra_context: telemetry data appended to LLM prompt only, not used in retrieval.
  - Fallback: if reranker disabled, returns cosine similarity scores.
"""

from __future__ import annotations
import logging
import requests
from dataclasses import dataclass, field
from typing import List

import chromadb

from config import cfg
from embedder import get_embedding_model

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass
class RetrievedChunk:
    """A single document chunk returned from the vector store."""
    chunk_id: str
    text: str
    source_file: str
    chunk_index: int
    similarity_score: float     # cosine similarity from bi-encoder [0, 1]
    rerank_score: float = 0.0   # cross-encoder relevance score [0, 1]

    @property
    def confidence(self) -> float:
        """The authoritative confidence score: cross-encoder if available, else cosine."""
        return self.rerank_score if self.rerank_score > 0 else self.similarity_score


@dataclass
class RAGResult:
    """Complete result of a single RAG query."""
    query: str
    answer: str
    retrieved_chunks: List[RetrievedChunk]
    prompt: str = field(repr=False)
    avg_confidence: float = 0.0   # average cross-encoder confidence of used chunks

    @property
    def sources(self) -> List[str]:
        """Deduplicated list of source filenames contributing to the answer."""
        return list(dict.fromkeys(c.source_file for c in self.retrieved_chunks))


# ---------------------------------------------------------------------------
# Prompt construction
# ---------------------------------------------------------------------------

_SYSTEM_LINES = [
    "You are SovereignNode AI, a precise factual assistant for industrial",
    "maintenance engineers.",
    "",
    "STRICT RULES:",
    "1. Answer ONLY using information from the CONTEXT passages below.",
    "2. If the context lacks enough information, say exactly:",
    "   'I do not have sufficient information in the provided documentation",
    "    to answer this question.'",
    "3. Never fabricate facts, specifications, or procedures.",
    "4. Always cite the source filename(s) at the end of your answer.",
    "5. Be concise, technically accurate, and professionally written.",
]
SYSTEM_PROMPT = "\n".join(_SYSTEM_LINES)


def build_prompt(query: str, chunks: List[RetrievedChunk], extra_context: str = "") -> str:
    """
    Assemble the full RAG prompt for Ollama.
    extra_context (e.g. live telemetry data) is appended after CONTEXT passages.
    """
    context_blocks = []
    for i, chunk in enumerate(chunks, start=1):
        score_label = f"relevance: {chunk.confidence:.3f}"
        block = (
            f"[Passage {i}] (Source: {chunk.source_file}, {score_label})\n"
            f"{chunk.text}"
        )
        context_blocks.append(block)
    context_str = "\n\n".join(context_blocks)

    user_message = "CONTEXT:\n" + context_str + "\n"
    if extra_context:
        user_message += f"\n{extra_context}\n"
    user_message += "\nQUESTION:\n" + query
    return user_message


# ---------------------------------------------------------------------------
# ChromaDB client
# ---------------------------------------------------------------------------

def _get_collection() -> chromadb.Collection:
    """Return the persistent ChromaDB collection (read-only for retrieval)."""
    client = chromadb.PersistentClient(path=str(cfg.CHROMA_PERSIST_DIR))
    return client.get_or_create_collection(
        name=cfg.CHROMA_COLLECTION_NAME,
        metadata={"hnsw:space": "cosine"},
    )


# ---------------------------------------------------------------------------
# Stage 1 — Bi-encoder retrieval (ChromaDB cosine)
# ---------------------------------------------------------------------------

def _bi_encoder_retrieve(query: str, n_candidates: int) -> List[RetrievedChunk]:
    """
    Fast initial retrieval using bi-encoder embeddings.
    Returns up to n_candidates chunks above the similarity threshold.
    """
    embedder = get_embedding_model()
    query_vector = embedder.encode_query(query)

    collection = _get_collection()
    try:
        results = collection.query(
            query_embeddings=[query_vector],
            n_results=min(n_candidates, collection.count()),
            include=["documents", "metadatas", "distances"],
        )
    except Exception as exc:
        logger.error("ChromaDB query failed: %s", exc)
        return []

    chunks: List[RetrievedChunk] = []
    for chunk_id, text, meta, dist in zip(
        results["ids"][0],
        results["documents"][0],
        results["metadatas"][0],
        results["distances"][0],
    ):
        similarity = max(0.0, 1.0 - dist)
        if similarity < cfg.SIMILARITY_THRESHOLD:
            continue
        chunks.append(RetrievedChunk(
            chunk_id=chunk_id,
            text=text,
            source_file=meta.get("filename", "unknown"),
            chunk_index=meta.get("chunk_index", 0),
            similarity_score=similarity,
        ))

    return chunks


# ---------------------------------------------------------------------------
# Stage 2 — Cross-encoder re-ranking
# ---------------------------------------------------------------------------

def _rerank(query: str, chunks: List[RetrievedChunk], top_k: int) -> List[RetrievedChunk]:
    """
    Re-rank chunks using the cross-encoder and assign rerank_score.
    Returns the top_k most relevant chunks sorted by descending rerank_score.
    """
    if not chunks:
        return []

    from reranker import get_reranker
    reranker = get_reranker()

    items = [(c.text, c) for c in chunks]
    ranked = reranker.rerank(query, items, top_k=top_k)

    result = []
    for score, chunk in ranked:
        chunk.rerank_score = score
        result.append(chunk)

    return result


# ---------------------------------------------------------------------------
# Full retrieve function: bi-encoder → cross-encoder → top-k
# ---------------------------------------------------------------------------

def retrieve(query: str, top_k: int = cfg.TOP_K_RESULTS) -> List[RetrievedChunk]:
    """
    Two-stage retrieval pipeline:
      1. Bi-encoder: over-fetch FETCH_K candidates from ChromaDB.
      2. Cross-encoder: re-rank by joint relevance, keep top_k.

    The returned chunks have both .similarity_score (cosine) and
    .rerank_score (cross-encoder). Use .confidence for the authoritative score.
    """
    fetch_k = cfg.RERANKER_FETCH_K if cfg.USE_RERANKER else top_k * 2

    # Stage 1: fast candidate retrieval
    candidates = _bi_encoder_retrieve(query, n_candidates=fetch_k)
    if not candidates:
        logger.warning("No candidates retrieved from ChromaDB for query: '%s'", query[:80])
        return []

    logger.info(
        "Bi-encoder retrieved %d candidates for query: '%s'",
        len(candidates), query[:80],
    )

    # Stage 2: cross-encoder re-ranking (if enabled)
    if cfg.USE_RERANKER and candidates:
        final_top_k = cfg.RERANKER_TOP_K
        chunks = _rerank(query, candidates, top_k=final_top_k)
        if chunks:
            avg_conf = sum(c.confidence for c in chunks) / len(chunks)
            logger.info(
                "Cross-encoder re-ranked to %d chunks — avg_confidence=%.3f (%.1f%%)",
                len(chunks), avg_conf, avg_conf * 100,
            )
            return chunks

    # Fallback: bi-encoder only (sort by cosine, keep top_k)
    candidates.sort(key=lambda c: c.similarity_score, reverse=True)
    return candidates[:top_k]


# ---------------------------------------------------------------------------
# Ollama generation helper
# ---------------------------------------------------------------------------

def _ollama_generate(system_prompt: str, user_message: str) -> str:
    """Call Ollama /api/chat with the given system + user messages."""
    payload = {
        "model": cfg.OLLAMA_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user",   "content": user_message},
        ],
        "stream": False,
        "options": {
            "temperature": cfg.TEMPERATURE,
            "top_p": cfg.TOP_P,
            "repeat_penalty": cfg.REPETITION_PENALTY,
            "num_predict": cfg.MAX_NEW_TOKENS,
        },
    }
    response = requests.post(
        f"{cfg.OLLAMA_BASE_URL}/api/chat",
        json=payload,
        timeout=300,
    )
    response.raise_for_status()
    return response.json()["message"]["content"].strip()


# ---------------------------------------------------------------------------
# Main RAG function
# ---------------------------------------------------------------------------

def query_rag(
    user_query: str,
    top_k: int = cfg.TOP_K_RESULTS,
    stream: bool = False,
    extra_context: str = "",
) -> RAGResult:
    """
    Execute the full two-stage RAG pipeline:
      1. Bi-encoder retrieval (ChromaDB, fast)
      2. Cross-encoder re-ranking (precise, 90%+ confidence)
      3. Build grounded prompt with re-ranked passages
      4. Generate answer via Ollama (Qwen 2.5-3B)
    """
    logger.info("RAG query: '%s'", user_query)

    # ── Stage 1+2: Retrieve and Re-rank ──────────────────────────────────────
    chunks = retrieve(user_query, top_k=top_k)
    if not chunks:
        return RAGResult(
            query=user_query,
            answer=(
                "I do not have sufficient information in the provided "
                "documentation to answer this question. "
                "(No relevant passages retrieved — run: python ingest.py)"
            ),
            retrieved_chunks=[],
            prompt="",
            avg_confidence=0.0,
        )

    avg_confidence = sum(c.confidence for c in chunks) / len(chunks)

    # ── Stage 3: Build grounded prompt ───────────────────────────────────────
    user_message = build_prompt(user_query, chunks, extra_context=extra_context)
    logger.debug("Prompt length: %d characters", len(user_message))

    # ── Stage 4: Generate answer via Ollama ──────────────────────────────────
    answer = _ollama_generate(SYSTEM_PROMPT, user_message)

    logger.info(
        "RAG complete — chunks=%d avg_confidence=%.3f (%.1f%%) chars=%d",
        len(chunks), avg_confidence, avg_confidence * 100, len(answer),
    )

    return RAGResult(
        query=user_query,
        answer=answer,
        retrieved_chunks=chunks,
        prompt=user_message,
        avg_confidence=avg_confidence,
    )
