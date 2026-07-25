# SecureDoc RAG — Issues & Fixes

## Issue 1: "I don't have that information in the provided documents" (wrong page retrieved)

**Symptom:** The model says it has no information, but the source cited is the correct page of the PDF.

**Root Cause:** The retriever was finding the right page but the extracted text was garbled or incomplete. PyPDFLoader (the default) struggles with complex PDFs that have tables, multi-column layouts, hex values (common in card spec documents like GlobalPlatform).

**Fix:**
- Switched PDF loader from `PyPDFLoader` to `PDFMinerLoader` in `ingest.py` for better text extraction.
- Increased `CHUNK_SIZE` from `1000` → `1500` and `CHUNK_OVERLAP` from `150` → `300` in `config.py`.
- Increased `TOP_K_RESULTS` from `4` → `6` in `config.py`.
- Install: `pip install pdfminer.six`

---

## Issue 2: Duplicate chunks — all retrieved results from the same TOC page

**Symptom:** All 6 retrieved chunks were from page 5 (Table of Contents). Queries for section names like "11.1.5.2 Response Chaining" matched the TOC entry instead of the actual section content.

**Root Cause:** Two sub-problems:
1. The vectorstore had duplicate data from multiple `python ingest.py` runs stacked on top of each other.
2. The Table of Contents contains all section names as dotted entries (e.g., `11.1.5.2 Response Chaining ......... 162`), so the embedding model matched TOC entries instead of real content.

**Fix:**
- Added `shutil.rmtree(VECTORSTORE_DIR)` at the start of `ingest.py` so it auto-clears old data before every ingestion run.
- Added a TOC page filter: skip pages where the content has more than 5 occurrences of `"..."` (dotted leader lines).
- Also skip pages with fewer than 100 characters of content after stripping.

---

## Issue 3: Per-page copyright boilerplate polluting embeddings

**Symptom:** Retrieval would find unrelated pages. Queries like "11.1.8 Key Type Coding" returned pages about totally different sections.

**Root Cause:** Every page of the GlobalPlatform PDF starts with a ~600-character copyright block:

```
GlobalPlatform Card Specification
Public Release v2.4   Page X / 286
Copyright © 2006-2025 GlobalPlatform, Inc. All Rights Reserved.
This document (and the information herein) is subject to updates...
...with the License is strictly prohibited.
```

With a chunk size of 1500, this boilerplate was **40% of every chunk**. Since it was identical across all pages, it made every chunk's embedding look similar, destroying retrieval accuracy.

**Note:** A regex-based approach failed because of a Unicode character (`\uf6d9`) used as the copyright symbol between "Copyright" and the year. The fix used a simple string split instead.

**Fix in `ingest.py`:**
```python
def strip_boilerplate(text: str) -> str:
    marker = "strictly prohibited."
    idx = text.find(marker)
    if idx != -1:
        text = text[idx + len(marker):]
    return text.strip()
```
Applied to every page after loading, before chunking.

---

## Issue 4: Model copying document text verbatim instead of explaining it

**Symptom:** The model's answer was an exact copy-paste of the table or paragraph from the PDF — not an explanation.

**Root Cause:** The system prompt in `app.py` was too restrictive:
> "Answer the question using ONLY the context provided. Do not make up or infer information beyond what is in the context."

This caused the LLM to treat the context as a template to copy rather than a source to reason from.

**Fix in `app.py` — updated `ANSWER_PROMPT`:**
```
You are a knowledgeable technical assistant specializing in smart card and security specifications.
Use the context below to answer the question clearly and in your own words.
Explain concepts, summarize tables, and highlight what is important — do not just copy text verbatim.
If the answer is not found in the context, say "I don't have that information in the provided documents."
```

---

## Issue 5: Section-reference queries still miss the right chunk (semantic search limitation)

**Symptom:** Asking "explain 11.1.8 Key Type Coding" retrieved pages about unrelated key topics. The actual section (page 164) was in the vectorstore but not retrieved.

**Root Cause:** Section 11.1.8 shared a chunk with section 11.1.7 (they were on the same page). The embedding of that mixed chunk was not strongly associated with "Key Type Coding" alone. Pure semantic (vector) search fails for exact section-number lookups because it finds *conceptually related* chunks, not *textually matching* ones.

**Fix:** Added hybrid retrieval — BM25 (keyword) + semantic (vector) combined.
- BM25 finds chunks containing the exact section number/title text.
- Semantic search finds conceptually related chunks.
- Results are merged and deduplicated.

```bash
pip install rank_bm25
```

**Note:** `EnsembleRetriever` from `langchain.retrievers` caused a `ModuleNotFoundError: No module named 'langchain_core.memory'` due to a broken import chain in the installed version of `langchain`. Fixed by implementing a simple custom `HybridRetriever` class in `app.py` instead:

```python
class HybridRetriever:
    def invoke(self, query):
        seen, results = set(), []
        for doc in bm25_retriever.invoke(query) + vector_retriever.invoke(query):
            key = doc.page_content[:100]
            if key not in seen:
                seen.add(key)
                results.append(doc)
        return results[:TOP_K_RESULTS * 2]
```

---

## Files Changed Summary

| File | What Changed |
|---|---|
| `config.py` | `CHUNK_SIZE` 1000→1500, `CHUNK_OVERLAP` 150→300, `TOP_K_RESULTS` 4→6 |
| `ingest.py` | Auto-clear vectorstore, skip TOC pages, strip per-page boilerplate |
| `app.py` | Updated system prompt, added BM25 hybrid retriever |

## Packages Installed

```bash
pip install pdfminer.six rank_bm25
```
