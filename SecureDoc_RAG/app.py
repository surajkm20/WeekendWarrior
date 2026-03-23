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
5. The chunks + chat history + question are injected into the prompt as context.
6. The prompt is sent to Ollama via HTTP.
7. Ollama runs llama3.2:3b and streams the answer back token by token.
8. Chainlit streams each token to the browser in real time.
9. Source citations (filename + page) are appended at the end.
10. The question + answer are saved to chat history for the next turn.

Run with:
    chainlit run app.py
Then open http://localhost:8000
"""

import chainlit as cl
from langchain_ollama import OllamaEmbeddings, ChatOllama
from langchain_chroma import Chroma
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import RunnablePassthrough

from config import (
    OLLAMA_BASE_URL,
    EMBED_MODEL,
    LLM_MODEL,
    TOP_K_RESULTS,
    VECTORSTORE_DIR,
    COLLECTION_NAME,
)

# --- CONTEXTUALIZE PROMPT ---
# Before searching the documents, we first rephrase the user's question into a
# "standalone" question that doesn't rely on prior conversation context.
#
# WHY? Because the retriever only sees the current question — not the history.
# If the user asks "what about the premium?", the retriever won't understand
# "what about" without knowing what was discussed before.
# This step rewrites it as e.g. "What is the premium amount in the policy?"
#
# {chat_history} = previous Human/Assistant turns formatted as text
# {question}     = the latest user question
CONTEXTUALIZE_PROMPT = """Given the chat history below and the latest user question, \
rewrite the question as a clear standalone question that can be understood \
without the chat history. Do NOT answer the question — only rewrite it if needed. \
If the question is already standalone, return it exactly as is.

Chat History:
{chat_history}

Latest Question: {question}

Standalone Question:"""


# --- MAIN ANSWER PROMPT ---
# This is the instruction we give to the LLM to generate the final answer.
# {chat_history} = previous turns (so the LLM can give consistent follow-up answers)
# {context}      = the most relevant document chunks retrieved from ChromaDB
# {question}     = the current user question (standalone version)
ANSWER_PROMPT = """You are a helpful assistant. Answer the question using ONLY the context provided below.
If the answer is not found in the context, say "I don't have that information in the provided documents."
Do not make up or infer information beyond what is in the context.

Chat History:
{chat_history}

Context:
{context}

Question: {question}

Answer:"""


def format_docs(docs):
    """
    Takes a list of retrieved document chunks and formats them into a single string.
    Each chunk includes the source filename and page number so the LLM knows where it came from.
    This formatted string becomes the {context} in the prompt.
    """
    return "\n\n".join(
        f"[Source: {doc.metadata.get('source', 'unknown')} | Page: {doc.metadata.get('page', 'N/A')}]\n{doc.page_content}"
        for doc in docs
    )


def format_history(chat_history: list) -> str:
    """
    Converts the chat history list into a readable string for the prompt.

    chat_history is stored as a list of dicts: [{"human": "...", "ai": "..."}, ...]
    This function formats it as:
        Human: ...
        Assistant: ...
    If there is no history yet, returns an empty string.
    """
    if not chat_history:
        return ""
    return "\n".join(
        f"Human: {turn['human']}\nAssistant: {turn['ai']}"
        for turn in chat_history
    )


@cl.on_chat_start
async def on_chat_start():
    """
    This function runs ONCE when a user opens the chat in the browser.

    FLOW:
    1. Connect to ChromaDB (the local vector database where your document chunks are stored).
    2. Create a retriever — this will search ChromaDB for relevant chunks when given a query.
    3. Connect to Ollama — the local server running the LLM (llama3.2:3b).
    4. Save the retriever, LLM, and an empty chat history in the user session.
    5. Send a welcome message to the browser.
    """

    # Step 1: Load the same embedding model used during ingestion.
    # This is needed to convert the user's question into a vector for similarity search.
    embeddings = OllamaEmbeddings(model=EMBED_MODEL, base_url=OLLAMA_BASE_URL)

    # Step 2: Connect to the existing ChromaDB vector store (created by ingest.py).
    vectorstore = Chroma(
        persist_directory=VECTORSTORE_DIR,      # folder where ChromaDB data is saved
        embedding_function=embeddings,
        collection_name=COLLECTION_NAME,
    )

    # Step 3: Create a retriever that fetches the top K most relevant chunks for any query.
    retriever = vectorstore.as_retriever(search_kwargs={"k": TOP_K_RESULTS})

    # Step 4: Connect to the local LLM via Ollama.
    llm = ChatOllama(model=LLM_MODEL, base_url=OLLAMA_BASE_URL)

    # Step 5: Save retriever, LLM, and empty history in the user session.
    # chat_history is a list of {"human": ..., "ai": ...} dicts — grows with each turn.
    cl.user_session.set("retriever", retriever)
    cl.user_session.set("llm", llm)
    cl.user_session.set("chat_history", [])

    # Send a welcome message to the browser.
    await cl.Message(
        content=f"SecureDoc RAG ready. Model: `{LLM_MODEL}` | Ask me anything about your documents."
    ).send()


@cl.on_message
async def on_message(message: cl.Message):
    """
    This function runs EVERY TIME the user sends a message in the chat.

    FLOW:
    1. Load the retriever, LLM, and chat history from the session.
    2. Format the chat history into a readable string.
    3. If there is history, rephrase the question into a standalone question using the LLM.
       If no history, use the question as-is.
    4. Use the standalone question to retrieve relevant chunks from ChromaDB.
    5. Build the final prompt: history + context + question → send to LLM.
    6. Stream the answer token by token to the browser.
    7. Append source citations at the bottom.
    8. Save this turn (question + answer) to chat history for future turns.
    """

    # Step 1: Load saved objects from the session.
    retriever    = cl.user_session.get("retriever")
    llm          = cl.user_session.get("llm")
    chat_history = cl.user_session.get("chat_history")  # list of past turns

    # Step 2: Format history into a string for use in prompts.
    history_text = format_history(chat_history)

    # Step 3: Rephrase the question into a standalone version if there is prior history.
    # This ensures the retriever can understand follow-up questions like "what about that?"
    if chat_history:
        contextualize_prompt = ChatPromptTemplate.from_template(CONTEXTUALIZE_PROMPT)
        contextualize_chain  = contextualize_prompt | llm | StrOutputParser()
        standalone_question  = contextualize_chain.invoke({
            "chat_history": history_text,
            "question": message.content,
        })
    else:
        # No history yet — the question is already standalone.
        standalone_question = message.content

    # Step 4: Retrieve the most relevant document chunks using the standalone question.
    # This converts the question to a vector and does similarity search in ChromaDB.
    source_docs = retriever.invoke(standalone_question)
    context     = format_docs(source_docs)

    # Collect unique source citations for display at the end.
    # e.g. "AckoPolicy.pdf (page 3)"
    sources = list({
        f"{doc.metadata.get('source', 'unknown')} (page {doc.metadata.get('page', 'N/A')})"
        for doc in source_docs
    })

    # Step 5: Build the final answer prompt with history + context + question.
    answer_prompt = ChatPromptTemplate.from_template(ANSWER_PROMPT)
    answer_chain  = answer_prompt | llm | StrOutputParser()

    # Step 6: Stream the answer token by token to the browser.
    # This gives the user the real-time typing effect.
    response = cl.Message(content="")
    await response.send()

    full_answer = ""
    async for chunk in answer_chain.astream({
        "chat_history": history_text,
        "context":      context,
        "question":     message.content,   # use original question (not rephrased) for the final answer
    }):
        await response.stream_token(chunk)
        full_answer += chunk

    # Step 7: Append source citations so the user knows which documents were used.
    if sources:
        citation_text = "\n\n---\n**Sources used:**\n" + "\n".join(f"- {s}" for s in sources)
        await response.stream_token(citation_text)

    # Finalize the message (marks streaming as complete).
    await response.update()

    # Step 8: Save this turn to chat history so future questions can reference it.
    # We keep only the last 6 turns to avoid making the prompt too long.
    chat_history.append({"human": message.content, "ai": full_answer})
    cl.user_session.set("chat_history", chat_history[-6:])
