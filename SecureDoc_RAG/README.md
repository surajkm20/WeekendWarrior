# SecureDoc RAG — Local AI Document Assistant

Runs 100% locally on your MacBook Air M2. No cloud, no data leaves your machine.

## Setup (one-time)

### 1. Install Ollama
```bash
brew install ollama
ollama serve   # keep this running in a terminal tab
```

### 2. Pull models
```bash
# For 8GB RAM:
ollama pull llama3.2:3b
# For 16GB RAM (better quality):
ollama pull llama3.1:8b

# Embedding model (required for both):
ollama pull nomic-embed-text
```
> If you use llama3.1:8b, update `LLM_MODEL` in `config.py`.

### 3. Create Python environment
```bash
cd /Users/zoro/Documents/SecureDoc_RAG
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```
> ChromaDB compilation takes ~2 min on first install — this is normal.

---

## Usage

### Step 1 — Add your documents
Drop PDF, DOCX, or TXT files into the `./docs/` folder.

### Step 2 — Ingest documents
```bash
source .venv/bin/activate
python ingest.py
```
Re-run this whenever you add new documents.

### Step 3 — Start the chat UI
```bash
chainlit run app.py
```
Opens at http://localhost:8000

---

## Project Structure
```
SecureDoc_RAG/
├── docs/           ← put your documents here
├── vectorstore/    ← ChromaDB data (auto-generated)
├── config.py       ← model settings, paths
├── ingest.py       ← document ingestion script
├── app.py          ← chat UI (Chainlit)
└── requirements.txt
```

## Config options (config.py)
| Setting | Default | Description |
|---|---|---|
| `LLM_MODEL` | `llama3.2:3b` | Change to `llama3.1:8b` for 16GB RAM |
| `CHUNK_SIZE` | `1000` | Characters per chunk |
| `TOP_K_RESULTS` | `4` | Chunks retrieved per query |
