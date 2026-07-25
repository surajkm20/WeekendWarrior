# config.py — Central configuration for AirLLM RAG

# --- LLM (AirLLM) ---
# AirLLM streams a model's layers from disk one at a time instead of loading the
# whole thing into memory, so even a 70B-parameter model can run on a machine
# with very little GPU/CPU RAM. The tradeoff is speed: every forward pass
# re-reads layer weights from disk, so generation is far slower than a normal
# in-memory model. See README "Performance notes" before picking a model here.
LLM_MODEL_ID     = "Qwen/Qwen2.5-1.5B-Instruct"
MAX_NEW_TOKENS   = 256
MAX_INPUT_TOKENS = 2048   # prompt truncation length passed to the tokenizer
# bitsandbytes-based compression ("4bit"/"8bit") is CUDA-only and does not work on
# Apple Silicon — leave this None on Mac. Only enable it on a CUDA-equipped machine.
COMPRESSION      = None

# --- Embeddings (sentence-transformers, runs locally, no server needed) ---
EMBED_MODEL      = "sentence-transformers/all-mpnet-base-v2"

CHUNK_SIZE       = 1500
CHUNK_OVERLAP    = 300
TOP_K_RESULTS    = 6          # number of chunks to retrieve per query

DOCS_DIR         = "./docs"
VECTORSTORE_DIR  = "./vectorstore"
COLLECTION_NAME  = "airllm_docs"
