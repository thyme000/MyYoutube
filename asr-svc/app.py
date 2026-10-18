import asyncio

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, HttpUrl

from engine import Worker
from model_runtime.application import lifespan_for, health_response
from model_runtime.settings import SERVICES
from model_runtime.context import BusinessContextMiddleware
from model_runtime import tracing
from model_runtime.monitoring import HttpMetricsMiddleware
from prometheus_client import make_asgi_app

identity = SERVICES["asr"]
app = FastAPI(title=identity.name, lifespan=lifespan_for(identity, Worker))
app.add_middleware(HttpMetricsMiddleware)
app.add_middleware(tracing.TracingMiddleware)
app.add_middleware(BusinessContextMiddleware)
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
    return health_response(app)


@app.post("/asr")
async def transcribe(request: AsrRequest):
    return await app.state.pool.run(request.model_dump(mode="json"))
