# config.py — Central configuration for SecureDoc RAG

OLLAMA_BASE_URL = "http://localhost:11434"
EMBED_MODEL     = "nomic-embed-text"

# Model selection based on your RAM:
#   8GB  → llama3.2:3b  (recommended, fast ~30 tok/s)
#   16GB → llama3.1:8b  (better reasoning, ~25 tok/s)
LLM_MODEL       = "llama3.2:3b"

CHUNK_SIZE      = 1000
CHUNK_OVERLAP   = 150
TOP_K_RESULTS   = 4           # number of chunks to retrieve per query

DOCS_DIR        = "./docs"
VECTORSTORE_DIR = "./vectorstore"
COLLECTION_NAME = "secure_docs"
