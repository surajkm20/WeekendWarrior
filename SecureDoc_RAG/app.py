"""
app.py — SecureDoc RAG Chat Interface

FRONTEND: Chainlit
- Chainlit is a Python library that creates a chat UI in your browser (like ChatGPT).
- It starts a local web server at http://localhost:8000.
- It communicates with this Python file using WebSockets (real-time, two-way connection).
- You don't write any HTML/CSS/JavaScript — Chainlit handles all of that.

HOW IT COMMUNICATES WITH THE MODEL:
- This file talks to Ollama (a local model server running at http://localhost:11434).
- Ollama loads the actual AI model (llama3.2:3b) and handles inference.
- LangChain acts as the middle layer — it chains together the retriever and the LLM.
- Communication is just HTTP requests, but everything stays on your machine (no internet).

FULL FLOW (what happens when you ask a question):
1. You type a question in the browser.
2. Chainlit sends it to this Python file via WebSocket.
3. If there is prior chat history, the question is first rephrased into a standalone question.
   (e.g. "what about the second one?" → "what is the second policy item?")
4. The standalone question is used to search ChromaDB for the most relevant chunks.
5. A cross-encoder re-ranker scores each candidate chunk against the query and keeps only
   the top-k most relevant ones — eliminating noise before the LLM ever sees the context.
6. The top-k chunks + chat history + question are injected into the prompt as context.
7. The prompt is sent to Ollama via HTTP.
8. Ollama streams the answer back token by token.
9. Chainlit streams each token to the browser in real time.
10. Source citations (filename + page) are appended at the end.
11. The question + answer are saved to chat history for the next turn.

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

import chainlit as cl
from langchain_ollama import OllamaEmbeddings, ChatOllama
from langchain_chroma import Chroma
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_community.retrievers import BM25Retriever
from sentence_transformers import CrossEncoder

from config import (
    OLLAMA_BASE_URL,
    EMBED_MODEL,
    LLM_MODEL,
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
ANSWER_PROMPT = """You are a precise technical assistant specializing in smart card and \
security specifications (EMV, ISO 7816, GlobalPlatform, etc.).
Answer using ONLY the context chunks provided below. Follow these principles:

1. FOCUS: Use only chunks that directly define or describe what the user asked about.
   Discard chunks about unrelated commands or concepts even if they appear in the context.

2. ACCURACY: Reproduce tables, tag values, byte encodings, and field values exactly as
   written — never paraphrase or infer values not explicitly stated in the context.
   When reading a command table, always identify the column header first (e.g. CLA, INS,
   P1, P2, Lc, Data) and map each value to its correct column — never swap row positions
   or rely on memory for column order. Read left-to-right from the header row.
   Do NOT expand acronyms (e.g. ARQC, AAC, AIP, AFL, ATC) unless the full expansion
   is explicitly written in the retrieved context. Use the acronym as-is if not defined there.

3. DATA FLOW: Always state clearly who creates a data object and who receives it
   (e.g. ICC → terminal, or terminal → ICC). Never reverse the direction.

4. CONSISTENCY: Before answering, check that your answer is internally consistent —
   e.g. if a Data field is present, Lc must also be present; if you give a CLA value,
   use only the one from the relevant command table, not a value from a different context.

5. HONESTY: If the context does not contain enough information to answer the question
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


@cl.on_chat_start
async def on_chat_start():
    embeddings = OllamaEmbeddings(model=EMBED_MODEL, base_url=OLLAMA_BASE_URL)

    vectorstore = Chroma(
        persist_directory=VECTORSTORE_DIR,
        embedding_function=embeddings,
        collection_name=COLLECTION_NAME,
    )

    # Build BM25 index over all stored chunks.
    all_chunks = vectorstore.get(include=["documents", "metadatas"])
    from langchain_core.documents import Document
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

    retriever = HybridRetriever()
    llm = ChatOllama(model=LLM_MODEL, base_url=OLLAMA_BASE_URL)

    cl.user_session.set("retriever", retriever)
    cl.user_session.set("llm", llm)
    cl.user_session.set("chat_history", [])

    await cl.Message(
        content=f"SecureDoc RAG ready. Model: `{LLM_MODEL}` | Ask me anything about your documents."
    ).send()


@cl.on_message
async def on_message(message: cl.Message):
    retriever    = cl.user_session.get("retriever")
    llm          = cl.user_session.get("llm")
    chat_history = cl.user_session.get("chat_history")

    history_text = format_history(chat_history)

    # Rephrase follow-up questions into standalone questions for retrieval.
    if chat_history:
        contextualize_prompt = ChatPromptTemplate.from_template(CONTEXTUALIZE_PROMPT)
        contextualize_chain  = contextualize_prompt | llm | StrOutputParser()
        standalone_question  = contextualize_chain.invoke({
            "chat_history": history_text,
            "question": message.content,
        })
    else:
        standalone_question = message.content

    # Retrieve a broad candidate set (BM25 + vector, TOP_K_RESULTS * 4 candidates).
    # Augment query with uppercase so BM25 matches EMV command names typed in lowercase.
    search_query = standalone_question + " " + standalone_question.upper()
    candidates = retriever.invoke(search_query)

    # Cross-encoder re-ranking: score every candidate against the actual query,
    # keep only the top TOP_K_RESULTS. This is the principled replacement for all
    # the keyword/phrase heuristics that were patching specific failure cases.
    source_docs = await rerank(standalone_question, candidates, TOP_K_RESULTS)

    context = format_docs(source_docs)
    sources = list({
        f"{doc.metadata.get('source', 'unknown')} (page {doc.metadata.get('page', 'N/A')})"
        for doc in source_docs
    })

    answer_prompt = ChatPromptTemplate.from_template(ANSWER_PROMPT)
    answer_chain  = answer_prompt | llm | StrOutputParser()

    response = cl.Message(content="")
    await response.send()

    full_answer = ""
    async for chunk in answer_chain.astream({
        "chat_history": history_text,
        "context":      context,
        "question":     message.content,
    }):
        await response.stream_token(chunk)
        full_answer += chunk

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
