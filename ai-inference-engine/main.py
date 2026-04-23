"""
main.py - SovereignNode AI — Offline RAG Inference Engine CLI
=============================================================
Interactive command-line interface for the local RAG system.

Subcommands:
  ingest   Ingest .txt/.pdf manuals into the vector store.
  query    Run a single query and print the grounded answer.
  chat     Start an interactive REPL session.

Usage:
  python main.py ingest --dir ./manuals [--reset]
  python main.py query "What is the maintenance interval for the pump seal?"
  python main.py chat
"""

from __future__ import annotations

import argparse
import logging
import sys
import time

from rich.console import Console
from rich.markdown import Markdown
from rich.panel import Panel
from rich.table import Table

from config import cfg
from ingest import ingest
from rag_engine import query_rag

console = Console()

BANNER = """
[bold cyan]SovereignNode AI[/bold cyan] [dim]— Offline Enterprise RAG Engine[/dim]
[dim]Model:[/dim]  {model}
[dim]Store:[/dim]  {store}
[dim]Embed:[/dim]  {embed}
"""


def _print_banner() -> None:
    console.print(Panel(
        BANNER.format(
            model=cfg.LLM_MODEL_ID,
            store=cfg.CHROMA_PERSIST_DIR,
            embed=cfg.EMBEDDING_MODEL_NAME,
        ),
        border_style="cyan",
        expand=False,
    ))


def _print_result(result) -> None:
    """Render a RAGResult as a rich-formatted panel."""
    # Sources table
    src_table = Table(show_header=True, header_style="bold magenta", box=None)
    src_table.add_column("File", style="cyan")
    src_table.add_column("Score", justify="right")
    src_table.add_column("Chunk", justify="right")
    for chunk in result.retrieved_chunks:
        src_table.add_row(
            chunk.source_file,
            f"{chunk.similarity_score:.3f}",
            str(chunk.chunk_index),
        )

    console.print()
    console.print(Panel(
        Markdown(result.answer),
        title="[bold green]Answer[/bold green]",
        border_style="green",
        padding=(1, 2),
    ))
    console.print(Panel(src_table, title="[bold yellow]Retrieved Sources[/bold yellow]",
                        border_style="yellow", expand=False))


# ---------------------------------------------------------------------------
# Subcommand: ingest
# ---------------------------------------------------------------------------

def cmd_ingest(args: argparse.Namespace) -> None:
    """Run the document ingestion pipeline."""
    logging.basicConfig(level=cfg.LOG_LEVEL,
                        format="%(asctime)s [%(levelname)s] %(name)s — %(message)s")
    ingest(manuals_dir=args.dir, reset=args.reset)


# ---------------------------------------------------------------------------
# Subcommand: query (single-shot)
# ---------------------------------------------------------------------------

def cmd_query(args: argparse.Namespace) -> None:
    """Answer a single query and exit."""
    logging.basicConfig(level=cfg.LOG_LEVEL,
                        format="%(asctime)s [%(levelname)s] %(name)s — %(message)s")
    _print_banner()

    console.print(f"[bold]Query:[/bold] {args.question}")
    t0 = time.perf_counter()

    with console.status("[cyan]Thinking…[/cyan]", spinner="dots"):
        result = query_rag(args.question, top_k=args.top_k, stream=False)

    elapsed = time.perf_counter() - t0
    _print_result(result)
    console.print(f"[dim]Completed in {elapsed:.1f}s[/dim]")


# ---------------------------------------------------------------------------
# Subcommand: chat (interactive REPL)
# ---------------------------------------------------------------------------

def cmd_chat(args: argparse.Namespace) -> None:
    """Start an interactive RAG chat session."""
    logging.basicConfig(level="WARNING")  # Suppress info logs in chat mode
    _print_banner()
    console.print("[dim]Type your question and press Enter. Type 'exit' or Ctrl-C to quit.[/dim]")
    console.print()

    while True:
        try:
            user_input = console.input("[bold cyan]You > [/bold cyan]").strip()
        except (KeyboardInterrupt, EOFError):
            console.print("\n[yellow]Goodbye.[/yellow]")
            break

        if not user_input:
            continue
        if user_input.lower() in ("exit", "quit", "q"):
            console.print("[yellow]Goodbye.[/yellow]")
            break

        t0 = time.perf_counter()
        console.print("[dim]Retrieving context and generating answer…[/dim]")

        result = query_rag(user_input, top_k=args.top_k, stream=args.stream)

        elapsed = time.perf_counter() - t0
        _print_result(result)
        console.print(f"[dim]{elapsed:.1f}s[/dim]\n")


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="sovereignnode-ai",
        description="SovereignNode AI — Offline enterprise RAG inference engine.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # ── ingest ────────────────────────────────────────────────────────────────
    p_ingest = sub.add_parser("ingest", help="Ingest manuals into the vector store.")
    p_ingest.add_argument("--dir", type=str, default=None,
                          help="Directory containing .txt/.pdf files.")
    p_ingest.add_argument("--reset", action="store_true",
                          help="Wipe existing collection before ingesting.")
    p_ingest.set_defaults(func=cmd_ingest)

    # ── query ─────────────────────────────────────────────────────────────────
    p_query = sub.add_parser("query", help="Run a single query and print the answer.")
    p_query.add_argument("question", type=str, help="Natural language query.")
    p_query.add_argument("--top-k", type=int, default=cfg.TOP_K_RESULTS,
                         help=f"Number of chunks to retrieve (default: {cfg.TOP_K_RESULTS}).")
    p_query.set_defaults(func=cmd_query)

    # ── chat ──────────────────────────────────────────────────────────────────
    p_chat = sub.add_parser("chat", help="Interactive REPL chat session.")
    p_chat.add_argument("--top-k", type=int, default=cfg.TOP_K_RESULTS,
                        help=f"Chunks per query (default: {cfg.TOP_K_RESULTS}).")
    p_chat.add_argument("--stream", action="store_true",
                        help="Stream tokens to stdout as they are generated.")
    p_chat.set_defaults(func=cmd_chat)

    return parser


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)
