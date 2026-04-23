"""
rag_engine.py - Retrieval-Augmented Generation Pipeline
========================================================
Full pipeline:  Query -> Embed -> ChromaDB Retrieve -> Prompt -> LLM -> Answer

Key design decisions:
  - Strict grounding: the SLM is instructed to ONLY answer from retrieved context.
  - Source attribution: RAGResult carries source filenames + cosine similarity scores.
  - Similarity thresholding: chunks below cfg.SIMILARITY_THRESHOLD are discarded.
  - Configurable top-K retrieval (cfg.TOP_K_RESULTS, default 3).
"""

from __future__ import annotations
import logging
from dataclasses import dataclass, field
from typing import List

import chromadb

from config import cfg
from embedder import get_embedding_model
from inference import get_language_model

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
    similarity_score: float           # cosine similarity [0, 1]


@dataclass
class RAGResult:
    """Complete result of a single RAG query."""
    query: str
    answer: str
    retrieved_chunks: List[RetrievedChunk]
    prompt: str = field(repr=False)   # excluded from repr to keep logs readable

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

# Chat tokens (filled in by the module that imports this at runtime)
_SYS_TOK  = '<|system|>'
_END_TOK  = '<|end|>'
_USER_TOK = '<|user|>'
_ASST_TOK = '<|assistant|>'


def build_prompt(query: str, chunks: List[RetrievedChunk]) -> str:
    """
    Assemble the full RAG prompt.

    Uses the Phi-3 / Mistral instruction-tuning chat format so the model
    respects the system instruction and treats context as authoritative.

    Parameters
    ----------
    query : str
        The user natural language question.
    chunks : list[RetrievedChunk]
        Top-K retrieved passages to inject as grounding context.

    Returns
    -------
    str
        Fully formatted prompt string ready for the tokeniser.
    """
    # Numbered context blocks with source attribution
    context_blocks = []
    for i, chunk in enumerate(chunks, start=1):
        block = (
            f"[Passage {i}] (Source: {chunk.source_file}, "
            f"similarity: {chunk.similarity_score:.3f})\n"
            f"{chunk.text}"
        )
        context_blocks.append(block)
    context_str = "\n\n".join(context_blocks)

    user_message = (
        "CONTEXT:\n"
        f"{context_str}\n\n"
        "QUESTION:\n"
        f"{query}"
    )

    prompt = (
        f"{_SYS_TOK}\n{SYSTEM_PROMPT}\n{_END_TOK}\n"
        f"{_USER_TOK}\n{user_message}\n{_END_TOK}\n"
        f"{_ASST_TOK}\n"
    )
    return prompt


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
# Retrieval
# ---------------------------------------------------------------------------

def retrieve(query: str, top_k: int = cfg.TOP_K_RESULTS) -> List[RetrievedChunk]:
    """
    Embed the query and retrieve the top-K most similar document chunks.

    Parameters
    ----------
    query : str
        The user natural language question.
    top_k : int
        Number of chunks to retrieve (default: cfg.TOP_K_RESULTS).

    Returns
    -------
    list[RetrievedChunk]
        Retrieved chunks sorted by descending similarity, filtered by threshold.
    """
    embedder = get_embedding_model()
    query_vector = embedder.encode_query(query)

    collection = _get_collection()
    results = collection.query(
        query_embeddings=[query_vector],
        n_results=top_k,
        include=["documents", "metadatas", "distances"],
    )

    chunks: List[RetrievedChunk] = []
    docs      = results["documents"][0]
    metas     = results["metadatas"][0]
    distances = results["distances"][0]
    ids       = results["ids"][0]

    for chunk_id, text, meta, dist in zip(ids, docs, metas, distances):
        # ChromaDB cosine distance: similarity = 1 - distance
        similarity = max(0.0, 1.0 - dist)
        if similarity < cfg.SIMILARITY_THRESHOLD:
            logger.debug(
                "Chunk %s discarded — similarity %.3f below threshold %.3f",
                chunk_id, similarity, cfg.SIMILARITY_THRESHOLD,
            )
            continue

        chunks.append(RetrievedChunk(
            chunk_id=chunk_id,
            text=text,
            source_file=meta.get("filename", "unknown"),
            chunk_index=meta.get("chunk_index", 0),
            similarity_score=similarity,
        ))

    logger.info("Retrieved %d/%d chunks for query: '%s'", len(chunks), top_k, query[:80])
    return chunks


# ---------------------------------------------------------------------------
# Main RAG function
# ---------------------------------------------------------------------------

def query_rag(
    user_query: str,
    top_k: int = cfg.TOP_K_RESULTS,
    stream: bool = False,
) -> RAGResult:
    """
    Execute the full RAG pipeline for a single natural language query.

    Parameters
    ----------
    user_query : str
        The maintenance engineer's question.
    top_k : int
        Number of context chunks to retrieve.
    stream : bool
        Stream generated tokens to stdout in real time.

    Returns
    -------
    RAGResult
        Contains the generated answer, retrieved passages, source files,
        and the full prompt for auditability.

    Raises
    ------
    RuntimeError
        If the ChromaDB collection is empty (ingestion was not run).
    """
    logger.info("RAG query: '%s'", user_query)

    # ── Step 1: Retrieve relevant chunks ─────────────────────────────────────
    chunks = retrieve(user_query, top_k=top_k)
    if not chunks:
        return RAGResult(
            query=user_query,
            answer=(
                "I do not have sufficient information in the provided "
                "documentation to answer this question. "
                "(No relevant passages were retrieved — check that documents "
                "have been ingested with: python ingest.py)"
            ),
            retrieved_chunks=[],
            prompt="",
        )

    # ── Step 2: Build grounded prompt ─────────────────────────────────────────
    prompt = build_prompt(user_query, chunks)
    logger.debug("Prompt length: %d characters", len(prompt))

    # ── Step 3: Generate answer via the quantized SLM ─────────────────────────
    llm = get_language_model()
    answer = llm.generate(prompt, stream=stream)

    logger.info("Answer generated (%d chars)", len(answer))
    return RAGResult(
        query=user_query,
        answer=answer,
        retrieved_chunks=chunks,
        prompt=prompt,
    )
