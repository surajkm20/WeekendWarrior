# AirLLM RAG — Local Document Assistant (AirLLM-powered)

Same RAG pipeline as [SecureDoc_RAG](../SecureDoc_RAG): chunk your documents, embed them,
store them in ChromaDB, retrieve with hybrid BM25 + vector search, re-rank with a
cross-encoder. The difference is the generation step — instead of talking to an Ollama
server, this project loads the LLM directly via [AirLLM](https://github.com/lyogavin/airllm),
which streams a model's layers from disk one at a time so models far larger than your
available RAM/VRAM can still run.

## ⚠️ Read this before picking a model

AirLLM's whole value proposition is running huge models (e.g. 70B) on hardware that could
never fit them in memory — but it does so by re-reading layer weights from disk on every
single forward pass. Keep in mind:

- **It's built around CUDA GPUs.** It targets NVIDIA GPUs with only a few GB of VRAM by
  offloading layers to system RAM/disk. On a machine with **no NVIDIA GPU** (e.g. Apple
  Silicon Macs), it falls back to CPU-only execution, which is dramatically slower and may
  not be practical for large models at all.
- **Disk space and download size are large.** `meta-llama/Meta-Llama-3.1-70B-Instruct`
  is ~140GB in full precision (AirLLM splits and can compress it, but you still need
  substantial free disk space to download and shard it on first run).
- **No token-by-token streaming.** AirLLM's `generate()` is a single blocking call —
  `app.py` runs it in a background thread and then streams the finished answer to the
  browser word-by-word to keep the UI responsive, but the model itself only "answers"
  once generation completes (which can take minutes per response for large models on
  modest hardware).
- **Gated models** like Llama 3.1 require you to accept Meta's license on
  [huggingface.co](https://huggingface.co) and run `huggingface-cli login` before
  `ingest.py`/`app.py` can download them.

If you're testing on a laptop without a discrete NVIDIA GPU, start with a smaller model
(e.g. `Qwen/Qwen2.5-7B-Instruct`) in `config.py` before attempting a 70B model.

## Setup (one-time)

### 1. Create Python environment
```bash
cd AirLLM_RAG
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. (Gated models only) Authenticate with HuggingFace
```bash
huggingface-cli login
```
Required for Llama models — accept the license at the model's HuggingFace page first.

### 3. Configure your model
Edit `config.py`:
- `LLM_MODEL_ID` — any HuggingFace model id AirLLM supports.
- `COMPRESSION` — `"4bit"` (default, faster/smaller) or `None` for full precision.

---

## Usage

### Step 1 — Add your documents
Drop PDF, DOCX, or TXT files into the `./docs/` folder.

### Step 2 — Ingest documents
```bash
source .venv/bin/activate
python ingest.py
```
Re-run this whenever you add new documents. This step uses sentence-transformers for
embeddings and does not touch AirLLM at all.

### Step 3 — Start the chat UI
```bash
chainlit run app.py
```
Opens at http://localhost:8000. The first message will be slow — AirLLM downloads and
shards the model into per-layer files on first load.

---

## Project Structure
```
AirLLM_RAG/
├── docs/           ← put your documents here
├── vectorstore/    ← ChromaDB data (auto-generated)
├── config.py       ← model settings, paths
├── ingest.py       ← document ingestion script (chunk + embed + store)
├── app.py          ← chat UI (Chainlit + AirLLM)
└── requirements.txt
```

## Config options (config.py)
| Setting | Default | Description |
|---|---|---|
| `LLM_MODEL_ID` | `meta-llama/Meta-Llama-3.1-70B-Instruct` | Any HF model id AirLLM supports |
| `COMPRESSION` | `4bit` | `None`, `"4bit"`, or `"8bit"` — quantizes layers to cut disk I/O |
| `MAX_NEW_TOKENS` | `256` | Max tokens generated per answer |
| `EMBED_MODEL` | `sentence-transformers/all-mpnet-base-v2` | Local embedding model, no server needed |
| `CHUNK_SIZE` / `CHUNK_OVERLAP` | `1500` / `300` | Characters per chunk |
| `TOP_K_RESULTS` | `6` | Chunks retrieved per query after re-ranking |
