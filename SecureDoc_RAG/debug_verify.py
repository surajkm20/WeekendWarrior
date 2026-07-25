"""
debug_verify.py — Inspect cross-encoder scores for the VERIFY command query.

Run with:
    python debug_verify.py
"""

from langchain_ollama import OllamaEmbeddings
from langchain_chroma import Chroma
from langchain_community.retrievers import BM25Retriever
from langchain_core.documents import Document
from sentence_transformers import CrossEncoder

from config import (
    OLLAMA_BASE_URL,
    EMBED_MODEL,
    TOP_K_RESULTS,
    VECTORSTORE_DIR,
    COLLECTION_NAME,
)

QUERY = "What does the VERIFY command do and what data does the terminal send to the ICC?"

print("Loading cross-encoder...")
cross_encoder = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")

print("Loading vectorstore...")
embeddings = OllamaEmbeddings(model=EMBED_MODEL, base_url=OLLAMA_BASE_URL)
vectorstore = Chroma(
    persist_directory=VECTORSTORE_DIR,
    embedding_function=embeddings,
    collection_name=COLLECTION_NAME,
)

print("Building BM25 index...")
all_chunks = vectorstore.get(include=["documents", "metadatas"])
docs_for_bm25 = [
    Document(page_content=txt, metadata=meta)
    for txt, meta in zip(all_chunks["documents"], all_chunks["metadatas"])
]
bm25_retriever = BM25Retriever.from_documents(docs_for_bm25, k=TOP_K_RESULTS * 2)

# Hybrid retrieval (same logic as app.py)
search_query = QUERY + " " + QUERY.upper()

bm25_results = bm25_retriever.invoke(search_query)
vector_results = vectorstore.similarity_search_with_score(search_query, k=TOP_K_RESULTS * 2)

rrf_scores: dict[str, float] = {}
doc_map: dict[str, object] = {}

for rank, doc in enumerate(bm25_results):
    key = doc.page_content[:100]
    rrf_scores[key] = rrf_scores.get(key, 0.0) + 1.0 / (rank + 60)
    doc_map[key] = doc

for rank, (doc, _) in enumerate(vector_results):
    key = doc.page_content[:100]
    rrf_scores[key] = rrf_scores.get(key, 0.0) + 1.0 / (rank + 60)
    doc_map[key] = doc

ranked_keys = sorted(rrf_scores, key=lambda k: rrf_scores[k], reverse=True)
candidates = [doc_map[k] for k in ranked_keys[:TOP_K_RESULTS * 4]]

print(f"\nHybrid retriever returned {len(candidates)} candidates.\n")

# Cross-encoder scoring
pairs = [(QUERY, doc.page_content) for doc in candidates]
scores = cross_encoder.predict(pairs)
ranked = sorted(zip(scores, candidates), key=lambda x: x[0], reverse=True)

print("=" * 80)
print(f"CROSS-ENCODER SCORES FOR: {QUERY!r}")
print("=" * 80)
for i, (score, doc) in enumerate(ranked):
    source = doc.metadata.get("source", "unknown")
    page   = doc.metadata.get("page", "N/A")
    kept   = "  <-- KEPT" if i < TOP_K_RESULTS else ""
    print(f"\n[{i+1:2d}] score={score:+.4f}  {source} | page {page}{kept}")
    print("-" * 60)
    print(doc.page_content[:400])
    print()

# Also grep vectorstore for chunks containing VERIFY command table markers
print("\n" + "=" * 80)
print("SEARCHING VECTORSTORE FOR CHUNKS CONTAINING 'INS' AND 'VERIFY'")
print("=" * 80)
hits = [
    doc for doc in docs_for_bm25
    if "VERIFY" in doc.page_content and "INS" in doc.page_content
]
print(f"Found {len(hits)} chunks with both 'VERIFY' and 'INS'.\n")
for doc in hits[:10]:
    source = doc.metadata.get("source", "unknown")
    page   = doc.metadata.get("page", "N/A")
    print(f"  {source} | page {page}")
    print(f"  {doc.page_content[:300]}")
    print()
