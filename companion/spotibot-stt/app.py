"""spotibot-stt: tiny local speech-to-text for Spotibot voice commands (metzfam).

POST /transcribe  body = raw PCM s16le, 16 kHz, mono  ->  {"text": "...", "ms": 412}
Audio is processed in memory only; nothing is written to disk or logged except timings.
"""
import os
import time
import threading

import numpy as np
from fastapi import FastAPI, Request, HTTPException
from faster_whisper import WhisperModel

MODEL = os.environ.get("STT_MODEL", "base.en")             # fast: every utterance (wake-word check)
ACCURATE = os.environ.get("STT_MODEL_ACCURATE", "")        # optional slower model (?mode=accurate); off by default
THREADS = int(os.environ.get("STT_THREADS", "2"))
PROMPT = os.environ.get("STT_PROMPT", "Spotibot, play Africa by Toto. Spotibot, skip. Spotibot, pause.")

app = FastAPI(title="spotibot-stt")
models = {
    "fast": WhisperModel(MODEL, device="cpu", compute_type="int8", cpu_threads=THREADS, download_root="/models"),
}
if ACCURATE:
    models["accurate"] = WhisperModel(ACCURATE, device="cpu", compute_type="int8", cpu_threads=THREADS, download_root="/models")
lock = threading.Lock()  # one decode at a time; CPU is shared with the rest of the server


@app.get("/health")
def health():
    return {"ok": True, "fast": MODEL, "accurate": ACCURATE}


@app.post("/transcribe")
async def transcribe(request: Request, mode: str = "fast"):
    raw = await request.body()
    if len(raw) < 16000 * 2 * 0.3:
        return {"text": "", "ms": 0}
    if len(raw) > 16000 * 2 * 20:
        raise HTTPException(413, "max 20 s")
    audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    t0 = time.time()
    with lock:
        segs, _ = models.get(mode, models["fast"]).transcribe(audio, language="en", beam_size=1, best_of=1, temperature=0.0,
                                   condition_on_previous_text=False, initial_prompt=PROMPT,
                                   without_timestamps=True, vad_filter=False)
        text = " ".join(s.text.strip() for s in segs if s.no_speech_prob < 0.8).strip()
    return {"text": text, "ms": int((time.time() - t0) * 1000)}
