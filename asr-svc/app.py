import asyncio
import multiprocessing
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, HttpUrl

from engine import Worker
from model_runtime.pool import BoundedPool
from model_runtime.discovery import registration
from prometheus_client import make_asgi_app

MODEL_DIRECTORY = os.getenv("ASR_MODEL_DIRECTORY", "/Users/thyme/project/whisper-models")
CONFIG = {"model": os.getenv("ASR_MODEL", f"{MODEL_DIRECTORY}/ggml-large-v3-turbo.bin"),
          "vad": os.getenv("ASR_VAD_MODEL", f"{MODEL_DIRECTORY}/ggml-silero-v5.1.2.bin"),
          "language": os.getenv("ASR_LANGUAGE", "zh"), "cue_length": int(os.getenv("ASR_CUE_LENGTH", "32")),
          "timeout": int(os.getenv("ASR_EXECUTION_TIMEOUT", "1200"))}
CONCURRENCY = max(1, int(os.getenv("ASR_CONCURRENCY", "1")))
QUEUE_SIZE = max(0, int(os.getenv("ASR_QUEUE_SIZE", "4")))


@asynccontextmanager
async def lifespan(app):
    pool = BoundedPool("asr", lambda: Worker(multiprocessing.get_context("spawn"), CONFIG), CONCURRENCY,
                       QUEUE_SIZE, int(os.getenv("ASR_QUEUE_TIMEOUT", "60")))
    app.state.pool = pool
    await pool.start()
    try:
        async with registration("asr-svc", 8089) as discovery:
            app.state.discovery = discovery
            yield
    finally:
        await pool.close()


app = FastAPI(title="asr-svc", lifespan=lifespan)
app.mount("/metrics", make_asgi_app())


class AsrRequest(BaseModel):
    sourceUrl: HttpUrl
    vttUploadUrl: HttpUrl
    segmentsUploadUrl: HttpUrl
    title: str = ""
    tags: str = ""
    description: str = ""
    language: str = ""


@app.get("/health")
async def health():
    pool = app.state.pool
    healthy = sum(worker.ready and worker.process.is_alive() for worker in pool.workers)
    discovery = app.state.discovery
    ready = pool.accepting and healthy > 0 and discovery.ready
    return JSONResponse({"ready": ready, "concurrency": CONCURRENCY, "healthyWorkers": healthy,
                         "admitted": pool.admitted, "discovery": discovery.status()},
                        status_code=200 if ready else 503)


@app.post("/asr")
async def transcribe(request: AsrRequest):
    return await app.state.pool.run(request.model_dump(mode="json"))
