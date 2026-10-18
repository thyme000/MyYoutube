from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, HttpUrl, Field
from prometheus_client import make_asgi_app
from engine import Worker
from model_runtime.application import lifespan_for, health_response
from model_runtime.settings import SERVICES
from model_runtime.context import BusinessContextMiddleware
from model_runtime import tracing
from model_runtime.monitoring import HttpMetricsMiddleware

identity = SERVICES["ocr"]
app = FastAPI(title=identity.name, lifespan=lifespan_for(identity, Worker))
app.add_middleware(HttpMetricsMiddleware)
app.add_middleware(tracing.TracingMiddleware)
app.add_middleware(BusinessContextMiddleware)
app.mount("/metrics", make_asgi_app())


class BatchRequest(BaseModel):
    urls: list[HttpUrl] = Field(min_length=1, max_length=50)


@app.get("/health")
async def health():
    return health_response(app)


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
