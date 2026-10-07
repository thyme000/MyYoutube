import io

import httpx
import numpy as np
from fastapi import FastAPI, HTTPException
from PIL import Image
from paddleocr import PaddleOCR
from pydantic import BaseModel

app = FastAPI(title="ocr-svc")
ocr = PaddleOCR(lang="ch")

MAX_BATCH = 50


def fetch_image(url: str) -> np.ndarray:
    try:
        resp = httpx.get(url, timeout=10)
        resp.raise_for_status()
    except httpx.HTTPError as e:
        raise ValueError(f"拉取图片失败: {e}")
    return np.asarray(Image.open(io.BytesIO(resp.content)).convert("RGB"))


def recognize(img: np.ndarray) -> str:
    result = ocr.predict(img)
    return "\n".join(t for page in result for t in page["rec_texts"])


@app.post("/ocr")
def ocr_url(url: str):
    try:
        return {"text": recognize(fetch_image(url))}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


class BatchRequest(BaseModel):
    urls: list[str]


@app.post("/ocr/batch")
def ocr_batch(req: BatchRequest):
    if not req.urls:
        raise HTTPException(status_code=400, detail="urls 不能为空")
    if len(req.urls) > MAX_BATCH:
        raise HTTPException(status_code=400, detail=f"单批最多 {MAX_BATCH} 张")

    results = []
    for url in req.urls:
        try:
            results.append({"url": url, "text": recognize(fetch_image(url))})
        except ValueError as e:
            results.append({"url": url, "error": str(e)})
    return {"results": results}
