# AirLLM RAG — Known Issues (Apple Silicon)

Encountered while first running this project on a MacBook Pro M2 Pro (32GB RAM, no NVIDIA GPU).
Kept separate from `TROUBLESHOOTING.md` since these are upstream `airllm` compatibility issues,
not bugs in this project's code.

## Issue 1: `ModuleNotFoundError: No module named 'mlx'` — RESOLVED

**Symptom:** `from airllm import AutoModel` fails immediately on import.

**Root Cause:** On Apple Silicon, `airllm` routes through an MLX-based backend
(`airllm/airllm_llama_mlx.py`) instead of the CUDA path. `mlx`/`mlx-lm` are required for this
backend but are **not declared as dependencies of the `airllm` package** — `pip install airllm`
alone is not enough on Mac.

**Fix — confirmed working:** `pip install mlx mlx-lm`. Now pinned in `requirements.txt` as a
platform marker (`sys_platform == "darwin" and platform_machine == "arm64"`) so it only installs
on Apple Silicon and doesn't affect CUDA machines. Verified installed (`mlx 0.32.0`, `mlx-lm
0.31.3`) and the import error does not recur — the pipeline progresses past this point to
model loading and layer-splitting (see Issue 3, which is the next thing hit, not this one).

**Reference:** [lyogavin/airllm#177 — Compression does not work with MLX / Apple Silicon](https://github.com/lyogavin/airllm/issues/177)

---

## Issue 2: `bitsandbytes`-based compression (`COMPRESSION = "4bit"` / `"8bit"`) does not work on Mac — NOT FIXABLE (upstream, hardcoded)

**Symptom:** Setting `COMPRESSION` to `"4bit"`/`"8bit"` in `config.py` raises
`AssertionError: Torch not compiled with CUDA enabled` on a non-CUDA machine.

**Root Cause — confirmed by reading the installed source:** it is tempting to assume installing
`bitsandbytes` is enough, since it does ship an Apple Silicon wheel now (tested here:
`bitsandbytes==0.50.0` installs cleanly on this Mac and can even instantiate
`bnb.nn.Linear8bitLt` on CPU). But the actual quantization AirLLM calls,
`airllm/utils.py::compress_layer_state_dict()`, hardcodes `.cuda()` on every tensor before
handing it to bitsandbytes:
```python
v_quant, quant_state = bnb.functional.quantize_nf4(v.cuda(), blocksize=64)        # 4bit path
v_quant, quant_state = bnb.functional.quantize_blockwise(v.cuda(), blocksize=2048) # 8bit path
```
`v.cuda()` unconditionally throws on this machine (confirmed directly: `torch.randn(4,4).cuda()`
→ `AssertionError: Torch not compiled with CUDA enabled`). Unlike Issue 1, this isn't a missing
pip package — bitsandbytes being present changes nothing, because AirLLM's own code never lets
bitsandbytes' CPU/MPS path run at all.

**Fix: none available at the application level.** There is no config flag, env var, or MLX-native
quantization path in this AirLLM version that avoids this call. The only way around it would be
patching AirLLM's own installed source to remove the `.cuda()` calls — unsupported, fragile, and
not something to do to a third-party package. This needs an upstream fix in `airllm` itself.

**What this project does about it:** `config.py` sets `COMPRESSION = None` by default and that is
the only viable setting on Apple Silicon today — not a workaround, the actual ceiling. Only set
`"4bit"`/`"8bit"` on a genuinely CUDA-equipped machine.

**Reference:** [lyogavin/airllm#177](https://github.com/lyogavin/airllm/issues/177) (community
thread covering the same MLX/Apple Silicon compression gap).

---

## Issue 3: `ValueError: Cannot index mlx array using the given type` on `model.generate()`

**Symptom:** Model loads and shards to disk successfully (`Loaded in ...s`), but the first
`generate()` call crashes:
```
File ".../airllm/airllm_llama_mlx.py", line 289, in model_generate
    x = self.tok_embeddings(x)
File ".../mlx/nn/layers/embedding.py", line 32, in __call__
    return self.weight[x]
ValueError: Cannot index mlx array using the given type.
```

**Root Cause:** AirLLM's README/examples (written for the CUDA backend) tokenize with
`return_tensors="pt"` and pass a **PyTorch tensor** into `model.generate(input_ids.cuda(), ...)`.
On Apple Silicon, `AutoModel.from_pretrained(...)` transparently swaps in the MLX backend
(`AirLLMLlamaMlx`), whose `generate()`/`model_generate()` feeds the input straight into an
`mlx.nn.Embedding`, which only accepts an `mx.array` — not a `torch.Tensor`. The two backends
are not input-compatible despite sharing the same `AutoModel.from_pretrained()` entry point and
`.generate()` method name.

**Workaround (not yet applied in `app.py`):** tokenize without `return_tensors` (or with
`return_tensors="np"`) and wrap the token ids in `mlx.core.array(...)` before calling
`model.generate()`:
```python
import mlx.core as mx
ids = model.tokenizer(prompt, return_tensors=None)["input_ids"]
x = mx.array([ids])
out = model.generate(x, max_new_tokens=20)   # returns a decoded string directly, not token ids
```
Note this also changes the return type: the MLX backend's `generate()` returns an already-decoded
`str`, whereas the CUDA backend's `generate()` returns a `GenerateOutput` with `.sequences` token
ids — `app.py`'s `airllm_generate()` would need a backend-aware branch to support both.

**Reference:** Confirmed independently by [Running AirLLM Locally on Apple Silicon: Not So Good
(dev.to)](https://dev.to/zhamdi/running-airllm-locally-on-apple-silicon-not-so-good-2f0f) — same
class of MLX-backend friction (also reports pre-quantized MLX-community models failing with
`FileNotFoundError: No safetensors found`, and ~10 minutes per query on a 7B model on a 48GB M4).

---

## Issue 4: Very slow / stalled model file downloads from HuggingFace Hub

**Symptom:** `Fetching 1 files: 100%| ... [08:04<00:00, 484.25s/it]` — a single ~3GB safetensors
file for a 1.5B model took over 8 minutes to fetch, despite the other 9 small config/tokenizer
files downloading in under a second.

**Root Cause:** Unauthenticated requests to the HF Hub are rate-limited
(`Warning: You are sending unauthenticated requests to the HF Hub`).

**Fix:** Set `HF_TOKEN` (`huggingface-cli login`) before running `ingest.py`/`app.py`, especially
before attempting any large/gated model.

---

## Summary: is AirLLM practical on Apple Silicon today?

Not for this project's original goal (running Meta-Llama-3.1-70B-Instruct). On this hardware:
- Issue 1 (missing `mlx`) is fully resolved — a one-line dependency fix, no longer a blocker.
- 4-bit/8-bit compression is unavailable (Issue 2), so a 70B model would need to shard at full
  precision — far more disk and RAM pressure than a CUDA box with compression enabled.
- The standard `generate()` call path is broken out of the box on the MLX backend (Issue 3,
  **still open**) and needs a backend-specific workaround that isn't upstream yet.
- Community reports (Issue 3 reference) show even a 7B model is ~10 minutes/query with no
  compression available — a 70B model would be substantially worse, if it completes at all.

AirLLM's disk-layer-streaming approach is designed around CUDA GPUs; on a CUDA machine none of
these four issues apply (compression works, and the documented PyTorch-tensor `generate()` API
is the one actually implemented). If the goal is specifically to test this project's RAG pipeline
end-to-end on this Mac, using a normal in-memory Apple Silicon-friendly runtime (e.g. Ollama, as
in `SecureDoc_RAG`, or `mlx-lm` directly without AirLLM's layer-splitting) would avoid all four
issues above.
