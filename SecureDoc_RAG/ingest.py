"""
ingest.py — Document Ingestion Pipeline

PURPOSE:
- This script reads your documents (PDF, DOCX, TXT) from the ./docs folder.
- It splits them into smaller chunks of text.
- It converts each chunk into a vector (a list of numbers that captures meaning).
- It stores those vectors in ChromaDB (a local vector database).

WHY DO WE NEED THIS?
- LLMs have a limited context window — you can't just dump an entire PDF into them.
- Instead, we pre-process the documents into chunks and store them as vectors.
- When a user asks a question, we search for the most relevant chunks and pass only those to the LLM.
- This is the "Retrieval" part of RAG (Retrieval-Augmented Generation).

FULL FLOW:
1. Load all PDF/DOCX/TXT files from ./docs
2. Split each document into overlapping chunks (e.g. 1000 chars with 150 char overlap)
3. Send each chunk to the embedding model (nomic-embed-text via Ollama)
4. The embedding model returns a vector (list of numbers) representing the meaning of that chunk
5. Store all vectors + original text in ChromaDB at ./vectorstore

Run this once after adding new documents:
    python ingest.py
"""

import os
from pathlib import Path

from langchain_community.document_loaders import (
    PyPDFLoader,      # reads PDF files, one page = one document
    Docx2txtLoader,   # reads .docx Word files
    TextLoader,       # reads plain .txt files
    DirectoryLoader,  # scans a folder and loads files matching a pattern
)
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_ollama import OllamaEmbeddings
from langchain_chroma import Chroma

from config import (
    OLLAMA_BASE_URL,
    EMBED_MODEL,
    CHUNK_SIZE,
    CHUNK_OVERLAP,
    DOCS_DIR,
    VECTORSTORE_DIR,
    COLLECTION_NAME,
)


def load_documents(docs_dir: str):
    """
    Scans the docs folder and loads all supported file types.

    - PyPDFLoader: splits a PDF into one document per page
    - Docx2txtLoader: extracts text from Word documents
    - TextLoader: reads plain text files

    Returns a flat list of all loaded documents (LangChain Document objects).
    Each Document has: page_content (the text) + metadata (source file, page number).
    """
    loaders = {
        "**/*.pdf":  PyPDFLoader,
        "**/*.docx": Docx2txtLoader,
        "**/*.txt":  TextLoader,
    }
    docs = []
    for glob_pattern, loader_cls in loaders.items():
        # DirectoryLoader walks the folder and applies the right loader for each file type
        loader = DirectoryLoader(docs_dir, glob=glob_pattern, loader_cls=loader_cls, silent_errors=True)
        loaded = loader.load()
        docs.extend(loaded)
        if loaded:
            print(f"  Loaded {len(loaded)} document(s) matching {glob_pattern}")
    return docs


def main():
    """
    Main ingestion pipeline. Runs the full flow:
    load documents → split into chunks → embed → store in ChromaDB.
    """

    # Check that the docs folder has files in it
    docs_path = Path(DOCS_DIR)
    if not any(docs_path.iterdir()):
        print(f"No documents found in {DOCS_DIR}. Add PDF, DOCX, or TXT files and re-run.")
        return

    # STEP 1: Load all documents from ./docs
    print(f"Loading documents from {DOCS_DIR}...")
    documents = load_documents(DOCS_DIR)
    if not documents:
        print("No supported documents found (PDF, DOCX, TXT).")
        return

    # STEP 2: Split documents into chunks.
    # RecursiveCharacterTextSplitter tries to split on paragraphs, then sentences, then words.
    # CHUNK_SIZE = max characters per chunk (e.g. 1000)
    # CHUNK_OVERLAP = how many characters the next chunk shares with the previous one.
    #   Overlap helps avoid losing context at chunk boundaries.
    print(f"\nSplitting into chunks (size={CHUNK_SIZE}, overlap={CHUNK_OVERLAP})...")
    splitter = RecursiveCharacterTextSplitter(chunk_size=CHUNK_SIZE, chunk_overlap=CHUNK_OVERLAP)
    chunks = splitter.split_documents(documents)
    print(f"  Total chunks: {len(chunks)}")

    # STEP 3: Load the embedding model.
    # nomic-embed-text converts text into a vector (768 numbers).
    # Vectors that are "close" to each other in space have similar meaning.
    # This runs locally via Ollama — no internet needed.
    print(f"\nGenerating embeddings with '{EMBED_MODEL}' (this may take a moment)...")
    embeddings = OllamaEmbeddings(model=EMBED_MODEL, base_url=OLLAMA_BASE_URL)

    # STEP 4: Store chunks + their vectors in ChromaDB.
    # Chroma.from_documents() calls the embedding model on each chunk and saves everything to disk.
    # After this, ./vectorstore contains the full searchable database.
    print(f"Storing in ChromaDB at {VECTORSTORE_DIR}...")
    Chroma.from_documents(
        documents=chunks,
        embedding=embeddings,
        persist_directory=VECTORSTORE_DIR,   # saved locally on disk
        collection_name=COLLECTION_NAME,
    )

    print(f"\nDone. Ingested {len(chunks)} chunks from {len(documents)} document(s).")
    print("You can now run: chainlit run app.py")


if __name__ == "__main__":
    main()
