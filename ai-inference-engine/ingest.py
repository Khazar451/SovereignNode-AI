"""
ingest.py — Document Ingestion, Chunking, and ChromaDB Indexing
===============================================================
Pipeline:
  1. Discover .txt and .pdf files in the configured manuals directory.
  2. Extract raw text (PDF via pypdf, TXT with encoding detection).
  3. Chunk text into overlapping windows to preserve cross-boundary context.
  4. Embed chunks using the sentence-transformer model.
  5. Upsert chunks + metadata into a persistent ChromaDB collection.

Run directly:
    python ingest.py                   # ingest all manuals
    python ingest.py --reset           # wipe collection, then ingest
    python ingest.py --dir ./manuals   # override manual directory
"""

from __future__ import annotations

import argparse
import hashlib
import logging
import sys
from pathlib import Path
from typing import Iterator, List, Tuple

import chardet
import chromadb
from pypdf import PdfReader
from rich.console import Console
from rich.progress import (
    BarColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
)
from rich.table import Table

from config import cfg
from embedder import get_embedding_model

logger = logging.getLogger(__name__)
console = Console()


# ─── Text Extraction ──────────────────────────────────────────────────────

def extract_text_from_pdf(path: Path) -> str:
    """Extract all text from a PDF file using pypdf (no poppler dependency)."""
    reader = PdfReader(str(path))
    pages = []
    for page in reader.pages:
        text = page.extract_text()
        if text:
            pages.append(text.strip())
    return "\n\n".join(pages)


def extract_text_from_txt(path: Path) -> str:
    """Read a .txt file, auto-detecting encoding with chardet."""
    raw = path.read_bytes()
    detected = chardet.detect(raw)
    encoding = detected.get("encoding") or "utf-8"
    return raw.decode(encoding, errors="replace")


def extract_text(path: Path) -> str:
    """Dispatch to the correct extractor based on file extension."""
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return extract_text_from_pdf(path)
    elif suffix == ".txt":
        return extract_text_from_txt(path)
    raise ValueError(f"Unsupported file type: {suffix}")


# ─── Chunking ─────────────────────────────────────────────────────────────

def chunk_text(
    text: str,
    chunk_size: int = cfg.CHUNK_SIZE,
    overlap: int = cfg.CHUNK_OVERLAP,
) -> List[str]:
    """
    Split text into fixed-size character windows with configurable overlap.

    The overlap ensures that information spanning a chunk boundary is
    present in both adjacent chunks, preventing retrieval gaps.

    Parameters
    ----------
    text : str
        Raw document text.
    chunk_size : int
        Target chunk size in characters.
    overlap : int
        Number of trailing characters from the previous chunk to prepend.

    Returns
    -------
    list[str]
        Non-empty chunks (whitespace-only chunks are discarded).
    """
    chunks: List[str] = []
    start = 0
    text_len = len(text)

    while start < text_len:
        end = min(start + chunk_size, text_len)
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        start += chunk_size - overlap  # slide forward with overlap

    return chunks


# ─── Document Discovery ───────────────────────────────────────────────────

def discover_documents(directory: Path) -> Iterator[Path]:
    """Yield all supported document paths under a directory (recursive)."""
    for ext in cfg.SUPPORTED_EXTENSIONS:
        yield from directory.rglob(f"*{ext}")


# ─── Deterministic Document ID ────────────────────────────────────────────

def make_chunk_id(source_path: Path, chunk_index: int) -> str:
    """
    Generate a stable, deterministic ID for a chunk.

    Built from a hash of (absolute_path + chunk_index) so re-running
    ingest produces the same IDs, enabling safe ChromaDB upserts.
    """
    key = f"{source_path.resolve()}::{chunk_index}"
    return hashlib.sha256(key.encode()).hexdigest()[:32]


# ─── ChromaDB Client ──────────────────────────────────────────────────────

def get_chroma_collection(reset: bool = False) -> chromadb.Collection:
    """
    Return (or create) the persistent ChromaDB collection.

    Parameters
    ----------
    reset : bool
        If True, delete and re-create the collection before returning.
    """
    client = chromadb.PersistentClient(path=str(cfg.CHROMA_PERSIST_DIR))

    if reset:
        try:
            client.delete_collection(cfg.CHROMA_COLLECTION_NAME)
            console.print(
                f"[yellow]⚠  Collection \'{cfg.CHROMA_COLLECTION_NAME}\' deleted.[/yellow]"
            )
        except Exception:
            pass  # Collection did not exist yet

    collection = client.get_or_create_collection(
        name=cfg.CHROMA_COLLECTION_NAME,
        metadata={"hnsw:space": "cosine"},  # cosine similarity metric
    )
    return collection


# ─── Main Ingestion Pipeline ──────────────────────────────────────────────

def ingest(manuals_dir: Path | None = None, reset: bool = False) -> None:
    """
    Full ingestion pipeline: discover → extract → chunk → embed → upsert.

    Parameters
    ----------
    manuals_dir : Path | None
        Directory of source documents. Defaults to cfg.MANUALS_DIR.
    reset : bool
        Wipe the existing vector store before indexing.
    """
    manuals_dir = manuals_dir or cfg.MANUALS_DIR
    manuals_dir = Path(manuals_dir)

    if not manuals_dir.exists():
        console.print(f"[red]ERROR: Manuals directory not found: {manuals_dir}[/red]")
        sys.exit(1)

    # ── Discover files ────────────────────────────────────────────────────
    docs = list(discover_documents(manuals_dir))
    if not docs:
        console.print(
            f"[yellow]No .txt or .pdf files found in {manuals_dir}[/yellow]"
        )
        return

    console.rule("[bold cyan]SovereignNode AI — Document Ingestion[/bold cyan]")
    console.print(f"[bold]Found {len(docs)} document(s)[/bold] in [cyan]{manuals_dir}[/cyan]")

    # ── Load infrastructure ───────────────────────────────────────────────
    collection = get_chroma_collection(reset=reset)
    embedder = get_embedding_model()

    # ── Per-document processing ───────────────────────────────────────────
    total_chunks = 0
    summary_rows: List[Tuple[str, int, int]] = []

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        TimeElapsedColumn(),
        console=console,
    ) as progress:
        doc_task = progress.add_task("[cyan]Ingesting documents…", total=len(docs))

        for doc_path in docs:
            progress.update(doc_task, description=f"[cyan]{doc_path.name}")

            try:
                # ── Extract ──────────────────────────────────────────────
                raw_text = extract_text(doc_path)
                if not raw_text.strip():
                    logger.warning("Skipping empty document: %s", doc_path)
                    progress.advance(doc_task)
                    continue

                # ── Chunk ────────────────────────────────────────────────
                chunks = chunk_text(raw_text)

                # ── Embed ────────────────────────────────────────────────
                embed_task = progress.add_task(
                    f"  [dim]Embedding {len(chunks)} chunks…", total=len(chunks)
                )
                embeddings = embedder.encode(chunks, show_progress=False)
                progress.update(embed_task, completed=len(chunks))

                # ── Upsert to ChromaDB ────────────────────────────────────
                ids = [make_chunk_id(doc_path, i) for i in range(len(chunks))]
                metadatas = [
                    {
                        "source": str(doc_path.resolve()),
                        "filename": doc_path.name,
                        "chunk_index": i,
                        "total_chunks": len(chunks),
                    }
                    for i in range(len(chunks))
                ]

                # Upsert in batches of 100 to stay within ChromaDB limits
                batch_size = 100
                for batch_start in range(0, len(chunks), batch_size):
                    bs = slice(batch_start, batch_start + batch_size)
                    collection.upsert(
                        ids=ids[bs],
                        embeddings=embeddings[bs],
                        documents=chunks[bs],
                        metadatas=metadatas[bs],
                    )

                summary_rows.append((doc_path.name, len(chunks), len(raw_text)))
                total_chunks += len(chunks)

            except Exception as exc:
                logger.exception("Failed to process %s: %s", doc_path.name, exc)
                console.print(f"[red]  ✗ {doc_path.name}: {exc}[/red]")

            finally:
                progress.advance(doc_task)

    # ── Summary table ─────────────────────────────────────────────────────
    table = Table(title="Ingestion Summary", show_header=True, header_style="bold magenta")
    table.add_column("File", style="cyan", no_wrap=True)
    table.add_column("Chunks", justify="right")
    table.add_column("Characters", justify="right")
    for name, chunks, chars in summary_rows:
        table.add_row(name, str(chunks), f"{chars:,}")

    console.print(table)
    console.print(
        f"[bold green]✓ Indexed {total_chunks} chunks from {len(summary_rows)} "
        f"document(s) into ChromaDB collection \'{cfg.CHROMA_COLLECTION_NAME}\'.[/bold green]"
    )
    console.print(
        f"  Vector store persisted to: [dim]{cfg.CHROMA_PERSIST_DIR}[/dim]"
    )


# ─── CLI Entry Point ──────────────────────────────────────────────────────

if __name__ == "__main__":
    logging.basicConfig(
        level=cfg.LOG_LEVEL,
        format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    )

    parser = argparse.ArgumentParser(
        description="Ingest enterprise manuals into the SovereignNode AI vector store."
    )
    parser.add_argument(
        "--dir",
        type=Path,
        default=None,
        help=f"Directory containing .txt / .pdf files (default: {cfg.MANUALS_DIR})",
    )
    parser.add_argument(
        "--reset",
        action="store_true",
        help="Delete the existing ChromaDB collection before ingesting.",
    )
    args = parser.parse_args()
    ingest(manuals_dir=args.dir, reset=args.reset)
