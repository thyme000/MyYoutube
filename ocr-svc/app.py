import multiprocessing
import os
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, HttpUrl, Field
from prometheus_client import make_asgi_app
from engine import Worker
from model_runtime.pool import BoundedPool
from model_runtime.discovery import registration

CONCURRENCY = max(1, int(os.getenv("OCR_CONCURRENCY", "1")))
QUEUE_SIZE = max(0, int(os.getenv("OCR_QUEUE_SIZE", "4")))


@asynccontextmanager
async def lifespan(app):
    pool = BoundedPool("ocr", lambda: Worker(multiprocessing.get_context("spawn"),
                       {"timeout": int(os.getenv("OCR_EXECUTION_TIMEOUT", "120"))}),
                       CONCURRENCY, QUEUE_SIZE, int(os.getenv("OCR_QUEUE_TIMEOUT", "60")))
    app.state.pool = pool
    await pool.start()
    try:
        async with registration("ocr-svc", 8088) as discovery:
            app.state.discovery = discovery
            yield
    finally:
        await pool.close()


app = FastAPI(title="ocr-svc", lifespan=lifespan)
app.mount("/metrics", make_asgi_app())


class BatchRequest(BaseModel):
    urls: list[HttpUrl] = Field(min_length=1, max_length=50)


@app.get("/health")
async def health():
    pool = app.state.pool
    healthy = sum(worker.ready and worker.process.is_alive() for worker in pool.workers)
    discovery = app.state.discovery
    ready = pool.accepting and healthy > 0 and discovery.ready
    return JSONResponse({"ready": ready, "concurrency": CONCURRENCY, "healthyWorkers": healthy,
                         "admitted": pool.admitted, "discovery": discovery.status()},
                        status_code=200 if ready else 503)


@app.post("/ocr")
async def ocr_url(url: HttpUrl):
    response = await app.state.pool.run({"urls": [str(url)]})
    item = response["results"][0]
    if "error" in item:
        raise HTTPException(502, item["error"])
    return {"text": item["text"]}


@app.post("/ocr/batch")
async def ocr_batch(request: BatchRequest):
    return await app.state.pool.run({"urls": [str(url) for url in request.urls]})
