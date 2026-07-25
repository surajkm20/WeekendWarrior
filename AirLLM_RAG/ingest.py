"""
ingest.py — Document Ingestion Pipeline

PURPOSE:
- This script reads your documents (PDF, DOCX, TXT) from the ./docs folder.
- It splits them into smaller chunks of text.
- It converts each chunk into a vector (a list of numbers that captures meaning)
  using a local sentence-transformers model (no server required).
- It stores those vectors in ChromaDB (a local vector database).

WHY DO WE NEED THIS?
- LLMs have a limited context window — you can't just dump an entire PDF into them.
- Instead, we pre-process the documents into chunks and store them as vectors.
- When a user asks a question, we search for the most relevant chunks and pass only those to the LLM.
- This is the "Retrieval" part of RAG (Retrieval-Augmented Generation).

FULL FLOW:
1. Load all PDF/DOCX/TXT files from ./docs
2. Split each document into overlapping chunks
3. Send each chunk to the embedding model (sentence-transformers, runs locally on CPU/GPU)
4. The embedding model returns a vector (list of numbers) representing the meaning of that chunk
5. Store all vectors + original text in ChromaDB at ./vectorstore

Run this once after adding new documents:
    python ingest.py
"""

import shutil
from pathlib import Path

import pdfplumber
from pypdf import PdfReader
from langchain_community.document_loaders import (
    Docx2txtLoader,
    TextLoader,
    DirectoryLoader,
)
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_chroma import Chroma

from config import (
    EMBED_MODEL,
    CHUNK_SIZE,
    CHUNK_OVERLAP,
    DOCS_DIR,
    VECTORSTORE_DIR,
    COLLECTION_NAME,
)


def get_page_labels(pdf_path) -> list[str] | None:
    """Return the PDF's printed page labels (e.g. 'i','ii','1','2'...) if the PDF has them set."""
    try:
        labels = list(PdfReader(str(pdf_path)).page_labels)
        return labels if labels else None
    except Exception:
        return None


def format_table(table: list[list]) -> str:
    """
    Format a pdfplumber table as pipe-separated rows so the LLM can read
    column relationships unambiguously (e.g. 'Name | Value' on one line).
    """
    lines = []
    for row in table:
        if not row:
            continue
        cells = [(str(c).strip() if c is not None else "") for c in row]
        if any(cells):
            lines.append(" | ".join(cells))
    return "\n".join(lines)


def load_documents(docs_dir: str):
    """
    Scans the docs folder and loads all supported file types.

    PDFs are loaded with pdfplumber:
      - extract_text() gives prose in reading order
      - extract_tables() gives structured table data; formatted as 'col1 | col2' rows
        so the LLM can read tabular data without column confusion.

    Returns a flat list of LangChain Document objects.
    Each Document has: page_content (text) + metadata (source, page number).
    """
    docs = []

    pdf_paths = list(Path(docs_dir).glob("**/*.pdf"))
    for pdf_path in pdf_paths:
        try:
            page_labels = get_page_labels(pdf_path)
            pages_loaded = 0
            pages_skipped = 0

            with pdfplumber.open(str(pdf_path)) as pdf:
                for i, page in enumerate(pdf.pages):
                    # Prose text in reading order
                    text = page.extract_text(x_tolerance=3, y_tolerance=3) or ""

                    # Structured tables appended below prose so column order is unambiguous
                    tables = page.extract_tables()
                    if tables:
                        table_blocks = [format_table(t) for t in tables if t]
                        table_blocks = [b for b in table_blocks if b]
                        if table_blocks:
                            text = text + "\n\n" + "\n\n".join(table_blocks)

                    text = text.strip()

                    # Skip TOC/index pages and near-empty pages
                    if len(text) <= 100:
                        pages_skipped += 1
                        continue
                    if text.count("...") / max(len(text), 1) >= 0.05:
                        pages_skipped += 1
                        continue

                    page_label = (
                        page_labels[i] if page_labels and i < len(page_labels) else i + 1
                    )

                    docs.append(Document(
                        page_content=text,
                        metadata={"source": str(pdf_path), "page": page_label},
                    ))
                    pages_loaded += 1

            print(f"  Loaded {pages_loaded} pages from {pdf_path.name} (skipped {pages_skipped} TOC/index pages)")

        except Exception as e:
            print(f"  Warning: could not load {pdf_path.name}: {e}")

    # Load DOCX and TXT via DirectoryLoader
    for glob_pattern, loader_cls in [("**/*.docx", Docx2txtLoader), ("**/*.txt", TextLoader)]:
        loader = DirectoryLoader(docs_dir, glob=glob_pattern, loader_cls=loader_cls, silent_errors=True)
        loaded = loader.load()
        docs.extend(loaded)
        if loaded:
            print(f"  Loaded {len(loaded)} document(s) matching {glob_pattern}")

    return docs


def main():
    """
    Main ingestion pipeline: load → split → embed → store in ChromaDB.
    """
    docs_path = Path(DOCS_DIR)
    if not docs_path.exists() or not any(docs_path.iterdir()):
        print(f"No documents found in {DOCS_DIR}. Add PDF, DOCX, or TXT files and re-run.")
        return

    # Clear old vectorstore to avoid duplicate chunks from previous ingestions
    if Path(VECTORSTORE_DIR).exists():
        shutil.rmtree(VECTORSTORE_DIR)
        print(f"Cleared old vectorstore at {VECTORSTORE_DIR}")

    print(f"Loading documents from {DOCS_DIR}...")
    documents = load_documents(DOCS_DIR)
    if not documents:
        print("No supported documents found (PDF, DOCX, TXT).")
        return

    print(f"\nSplitting into chunks (size={CHUNK_SIZE}, overlap={CHUNK_OVERLAP})...")
    splitter = RecursiveCharacterTextSplitter(chunk_size=CHUNK_SIZE, chunk_overlap=CHUNK_OVERLAP)
    chunks = splitter.split_documents(documents)
    print(f"  Total chunks: {len(chunks)}")

    print(f"\nGenerating embeddings with '{EMBED_MODEL}' (this may take a moment)...")
    embeddings = HuggingFaceEmbeddings(model_name=EMBED_MODEL)

    print(f"Storing in ChromaDB at {VECTORSTORE_DIR}...")
    Chroma.from_documents(
        documents=chunks,
        embedding=embeddings,
        persist_directory=VECTORSTORE_DIR,
        collection_name=COLLECTION_NAME,
    )

    print(f"\nDone. Ingested {len(chunks)} chunks from {len(documents)} document(s).")
    print("You can now run: chainlit run app.py")


if __name__ == "__main__":
    main()
