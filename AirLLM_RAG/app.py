"""
app.py — AirLLM RAG Chat Interface

FRONTEND: Chainlit
- Chainlit is a Python library that creates a chat UI in your browser (like ChatGPT).
- It starts a local web server at http://localhost:8000.
- It communicates with this Python file using WebSockets (real-time, two-way connection).

HOW IT COMMUNICATES WITH THE MODEL:
- This file loads the LLM directly with AirLLM — no model server (like Ollama) involved.
- AirLLM splits the model into per-layer files on disk and streams each layer onto the
  GPU/CPU one at a time during inference. This is what lets a 70B-parameter model run
  on hardware that could never fit it in memory all at once — the tradeoff is that
  every generated response re-reads layer weights from disk, so it is much slower
  than a normally-loaded model. See README "Performance notes".
- Embeddings are generated locally with sentence-transformers (also no server needed).

FULL FLOW (what happens when you ask a question):
1. You type a question in the browser.
2. Chainlit sends it to this Python file via WebSocket.
3. If there is prior chat history, the question is first rephrased into a standalone question.
4. The standalone question is used to search ChromaDB for the most relevant chunks
   (hybrid BM25 + vector search).
5. A cross-encoder re-ranker scores each candidate chunk against the query and keeps only
   the top-k most relevant ones — eliminating noise before the LLM ever sees the context.
6. The top-k chunks + chat history + question are assembled into a prompt.
7. The prompt is run through the AirLLM model (blocking call, executed in a thread).
8. Because AirLLM has no native token streaming, the finished answer is streamed to the
   browser word-by-word to mimic a live-typing effect.
9. Source citations (filename + page) are appended at the end.
10. The question + answer are saved to chat history for the next turn.

Run with:
    chainlit run app.py
Then open http://localhost:8000
"""

# Python 3.14 + uvicorn compatibility fix:
# uvicorn 0.42+ creates ASGI tasks with an empty contextvars.Context() to avoid
# context pollution (cpython#140947). This causes asyncio.current_task() to return
# None inside anyio's CancelScope, breaking anyio.to_thread.run_sync and anyio.open_file
# (used by starlette's FileResponse for favicon/logo/assets).
# Fix: replace anyio.to_thread.run_sync with asyncio's run_in_executor when current_task()
# is None, bypassing anyio's CancelScope machinery entirely.
import asyncio as _asyncio
import anyio.to_thread as _anyio_to_thread

_orig_anyio_run_sync = _anyio_to_thread.run_sync

async def _patched_anyio_run_sync(func, *args, abandon_on_cancel=False, cancellable=None, limiter=None):
    if _asyncio.current_task() is None:
        loop = _asyncio.get_running_loop()
        return await loop.run_in_executor(None, func, *args)
    return await _orig_anyio_run_sync(func, *args, abandon_on_cancel=abandon_on_cancel,
                                      cancellable=cancellable, limiter=limiter)

_anyio_to_thread.run_sync = _patched_anyio_run_sync
import anyio as _anyio
_anyio.to_thread.run_sync = _patched_anyio_run_sync

import torch
import chainlit as cl
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_community.retrievers import BM25Retriever
from sentence_transformers import CrossEncoder
from airllm import AutoModel

from config import (
    LLM_MODEL_ID,
    MAX_NEW_TOKENS,
    MAX_INPUT_TOKENS,
    COMPRESSION,
    EMBED_MODEL,
    TOP_K_RESULTS,
    VECTORSTORE_DIR,
    COLLECTION_NAME,
)

# Cross-encoder re-ranker loaded once at startup.
# Given a (query, passage) pair it returns a relevance score.
# We retrieve a broad candidate set (TOP_K_RESULTS * 4) then keep only the
# top TOP_K_RESULTS chunks by cross-encoder score — removing noise before
# the LLM ever sees the context.
print("Loading cross-encoder re-ranker...")
cross_encoder = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")
print("Cross-encoder ready.")

# AirLLM model loaded once at startup. This downloads/splits the model into
# per-layer shards on first run (can take a long time and a lot of disk space
# for large models), then streams layers from disk on every generate() call.
print(f"Loading {LLM_MODEL_ID} via AirLLM (compression={COMPRESSION})...")
llm_model = AutoModel.from_pretrained(LLM_MODEL_ID, compression=COMPRESSION)
_device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"AirLLM model ready (device={_device}).")


# --- CONTEXTUALIZE PROMPT ---
CONTEXTUALIZE_PROMPT = """Given the chat history below and the latest user question, \
rewrite the question as a clear standalone question that can be understood \
without the chat history. Do NOT answer the question — only rewrite it if needed. \
If the question is already standalone, return it exactly as is.

Chat History:
{chat_history}

Latest Question: {question}

Standalone Question:"""


# --- MAIN ANSWER PROMPT ---
ANSWER_PROMPT = """You are a precise assistant answering questions about the user's documents.
Answer using ONLY the context chunks provided below. Follow these principles:

1. FOCUS: Use only chunks that directly relate to what the user asked about.
   Discard chunks about unrelated topics even if they appear in the context.

2. ACCURACY: Reproduce facts, figures, and quoted text exactly as written —
   never paraphrase or infer values not explicitly stated in the context.

3. HONESTY: If the context does not contain enough information to answer the question
   accurately, say so explicitly. Do not guess, infer, or substitute a related concept.

Chat History:
{chat_history}

Context:
{context}

Question: {question}

Answer:"""


def format_docs(docs):
    return "\n\n".join(
        f"[Source: {doc.metadata.get('source', 'unknown')} | Page: {doc.metadata.get('page', 'N/A')}]\n{doc.page_content}"
        for doc in docs
    )


def format_history(chat_history: list) -> str:
    if not chat_history:
        return ""
    return "\n".join(
        f"Human: {turn['human']}\nAssistant: {turn['ai']}"
        for turn in chat_history
    )


async def rerank(query: str, docs: list, top_k: int) -> list:
    """Score each doc against the query with the cross-encoder and return top_k."""
    if not docs:
        return docs
    pairs = [(query, doc.page_content) for doc in docs]
    loop = _asyncio.get_running_loop()
    scores = await loop.run_in_executor(None, cross_encoder.predict, pairs)
    ranked = sorted(zip(scores, docs), key=lambda x: x[0], reverse=True)
    return [doc for _, doc in ranked[:top_k]]


def airllm_generate(prompt: str) -> str:
    """
    Blocking call into AirLLM. Runs on a background thread (see call sites) since
    it streams model layers from disk and will otherwise freeze the event loop.
    """
    input_tokens = llm_model.tokenizer(
        [prompt],
        return_tensors="pt",
        return_attention_mask=False,
        truncation=True,
        max_length=MAX_INPUT_TOKENS,
        padding=False,
    )
    input_ids = input_tokens["input_ids"]
    if _device == "cuda":
        input_ids = input_ids.cuda()
    input_length = input_ids.shape[-1]

    generation_output = llm_model.generate(
        input_ids,
        max_new_tokens=MAX_NEW_TOKENS,
        use_cache=True,
        return_dict_in_generate=True,
    )

    new_tokens = generation_output.sequences[0][input_length:]
    return llm_model.tokenizer.decode(new_tokens, skip_special_tokens=True).strip()


@cl.on_chat_start
async def on_chat_start():
    embeddings = HuggingFaceEmbeddings(model_name=EMBED_MODEL)

    vectorstore = Chroma(
        persist_directory=VECTORSTORE_DIR,
        embedding_function=embeddings,
        collection_name=COLLECTION_NAME,
    )

    # Build BM25 index over all stored chunks.
    all_chunks = vectorstore.get(include=["documents", "metadatas"])
    docs_for_bm25 = [
        Document(page_content=txt, metadata=meta)
        for txt, meta in zip(all_chunks["documents"], all_chunks["metadatas"])
    ]
    bm25_retriever = BM25Retriever.from_documents(docs_for_bm25, k=TOP_K_RESULTS * 2)

    # Hybrid retriever: BM25 + vector merged with Reciprocal Rank Fusion.
    # Returns a wider candidate set (TOP_K_RESULTS * 4) so the cross-encoder
    # has enough candidates to pick the truly relevant ones from.
    class HybridRetriever:
        def invoke(self, query):
            bm25_results = bm25_retriever.invoke(query)
            vector_results = vectorstore.similarity_search_with_score(query, k=TOP_K_RESULTS * 2)

            rrf_scores: dict[str, float] = {}
            doc_map: dict[str, object] = {}

            for rank, doc in enumerate(bm25_results):
                key = doc.page_content
                rrf_scores[key] = rrf_scores.get(key, 0.0) + 1.0 / (rank + 60)
                doc_map[key] = doc

            for rank, (doc, _) in enumerate(vector_results):
                key = doc.page_content
                rrf_scores[key] = rrf_scores.get(key, 0.0) + 1.0 / (rank + 60)
                doc_map[key] = doc

            ranked_keys = sorted(rrf_scores, key=lambda k: rrf_scores[k], reverse=True)
            return [doc_map[k] for k in ranked_keys[:TOP_K_RESULTS * 4]]

    cl.user_session.set("retriever", HybridRetriever())
    cl.user_session.set("chat_history", [])

    await cl.Message(
        content=(
            f"AirLLM RAG ready. Model: `{LLM_MODEL_ID}` (compression={COMPRESSION}) | "
            "Ask me anything about your documents.\n\n"
            "⚠️ AirLLM streams model layers from disk on every request, so responses "
            "can take a while — this is expected, not a bug."
        )
    ).send()


@cl.on_message
async def on_message(message: cl.Message):
    retriever    = cl.user_session.get("retriever")
    chat_history = cl.user_session.get("chat_history")

    history_text = format_history(chat_history)
    loop = _asyncio.get_running_loop()

    # Rephrase follow-up questions into standalone questions for retrieval.
    if chat_history:
        contextualize_prompt = CONTEXTUALIZE_PROMPT.format(
            chat_history=history_text, question=message.content
        )
        standalone_question = await loop.run_in_executor(None, airllm_generate, contextualize_prompt)
    else:
        standalone_question = message.content

    # Retrieve a broad candidate set (BM25 + vector, TOP_K_RESULTS * 4 candidates),
    # then cross-encoder re-rank down to TOP_K_RESULTS truly relevant chunks.
    candidates = retriever.invoke(standalone_question)
    source_docs = await rerank(standalone_question, candidates, TOP_K_RESULTS)

    context = format_docs(source_docs)
    sources = list({
        f"{doc.metadata.get('source', 'unknown')} (page {doc.metadata.get('page', 'N/A')})"
        for doc in source_docs
    })

    answer_prompt = ANSWER_PROMPT.format(
        chat_history=history_text, context=context, question=message.content
    )

    response = cl.Message(content="")
    await response.send()

    # AirLLM has no native token streaming — generate the full answer, then
    # stream it word-by-word so the UI still feels responsive.
    full_answer = await loop.run_in_executor(None, airllm_generate, answer_prompt)
    for word in full_answer.split(" "):
        await response.stream_token(word + " ")

    if sources:
        items = "\n".join(f"- {s}" for s in sources)
        source_element = cl.Text(
            name=f"Sources used ({len(sources)})",
            content=items,
            display="side",
        )
        response.elements = [source_element]
        await response.stream_token(f"\n\n📄 *Sources used ({len(sources)})*")

    await response.update()

    chat_history.append({"human": message.content, "ai": full_answer})
    cl.user_session.set("chat_history", chat_history[-6:])
