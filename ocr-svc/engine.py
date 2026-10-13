import logging
import os
import time
import httpx
from model_runtime.process import ProcessWorker

logger = logging.getLogger(__name__)


def recognize_image(model, client, url):
    import numpy as np
    from PIL import Image
    import tempfile
    import resource
    import sys
    max_bytes = int(os.getenv("OCR_MAX_IMAGE_BYTES", str(32 * 1024 * 1024)))
    max_pixels = int(os.getenv("OCR_MAX_IMAGE_PIXELS", "40000000"))
    max_edge = int(os.getenv("OCR_MAX_IMAGE_EDGE", "16000"))
    with tempfile.TemporaryFile() as downloaded:
        size = 0
        with client.stream("GET", url) as response:
            response.raise_for_status()
            for chunk in response.iter_bytes(64 * 1024):
                size += len(chunk)
                if size > max_bytes:
                    raise ValueError("OCR image exceeds byte budget")
                downloaded.write(chunk)
        downloaded.seek(0)
        with Image.open(downloaded) as source:
            if source.width * source.height > max_pixels or max(source.size) > max_edge:
                raise ValueError("OCR image exceeds dimension budget")
            with source.convert("RGB") as image:
                logger.info("OCR image decoded pid=%s width=%s height=%s bytes=%s", os.getpid(), image.width, image.height, size)
                pixels = np.asarray(image)
                try:
                    return "\n".join(t for page in model.predict_iter(pixels) for t in page["rec_texts"])
                finally:
                    del pixels
                    logger.info("OCR image released pid=%s peakRssBytes=%s", os.getpid(),
                                resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if sys.platform == "darwin" else 1024))


def serve(connection, config):
    os.setsid()
    try:
        from paddleocr import PaddleOCR
        model = PaddleOCR(lang="ch", use_doc_unwarping=False, text_recognition_batch_size=2)
        with httpx.Client(timeout=10, follow_redirects=False) as client:
            connection.send({"ready": True})
            while True:
                request = connection.recv()
                results = []
                for index, url in enumerate(request["urls"]):
                    started = time.monotonic()
                    logger.info("OCR image started pid=%s index=%s count=%s", os.getpid(), index, len(request["urls"]))
                    try:
                        text = recognize_image(model, client, url)
                        results.append({"url": url, "text": text})
                        logger.info("OCR image completed pid=%s index=%s elapsed=%.3fs", os.getpid(), index, time.monotonic() - started)
                    except Exception as error:
                        logger.error("OCR image failed pid=%s index=%s elapsed=%.3fs error=%s", os.getpid(), index,
                                     time.monotonic() - started, type(error).__name__, exc_info=not isinstance(error, httpx.HTTPError))
                        results.append({"url": url, "error": type(error).__name__})
                connection.send({"results": results})
    except BaseException as error:
        logger.exception("OCR worker failed pid=%s", os.getpid())
        try:
            connection.send({"error": type(error).__name__})
        except (BrokenPipeError, EOFError):
            pass
    finally:
        connection.close()


class Worker(ProcessWorker):
    def __init__(self, context, config):
        super().__init__(context, config, serve)
