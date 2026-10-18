import logging
import os
import time
import httpx
from model_runtime.process import ProcessWorker
from model_runtime.resources import peak_rss_bytes

logger = logging.getLogger(__name__)


def recognize_image(model, client, url, config):
    import numpy as np
    from PIL import Image
    import tempfile
    with tempfile.TemporaryFile() as downloaded:
        size = 0
        with client.stream("GET", url) as response:
            response.raise_for_status()
            for chunk in response.iter_bytes(64 * 1024):
                size += len(chunk)
                if size > config.max_image_bytes:
                    raise ValueError("OCR image exceeds byte budget")
                downloaded.write(chunk)
        downloaded.seek(0)
        with Image.open(downloaded) as source:
            if source.width * source.height > config.max_image_pixels or max(source.size) > config.max_image_edge:
                raise ValueError("OCR image exceeds dimension budget")
            with source.convert("RGB") as image:
                logger.info("OCR image decoded pid=%s width=%s height=%s bytes=%s", os.getpid(), image.width, image.height, size)
                pixels = np.asarray(image)
                try:
                    return "\n".join(t for page in model.predict_iter(pixels) for t in page["rec_texts"])
                finally:
                    del pixels
                    logger.info("OCR image released pid=%s peakRssBytes=%s", os.getpid(),
                                peak_rss_bytes())


def model_options(config):
    import yaml
    options = {"text_recognition_batch_size": config.recognition_batch_size,
               "use_textline_orientation": config.textline_orientation is not None,
               "use_doc_orientation_classify": config.doc_orientation is not None,
               "use_doc_unwarping": config.doc_unwarping is not None}
    for field, prefix in (("detection", "text_detection"), ("recognition", "text_recognition"),
                          ("textline_orientation", "textline_orientation"), ("doc_orientation", "doc_orientation_classify"),
                          ("doc_unwarping", "doc_unwarping")):
        directory = getattr(config, field)
        if directory is None:
            continue
        with (directory / "inference.yml").open(encoding="utf-8") as file:
            name = yaml.safe_load(file).get("Global", {}).get("model_name")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"Missing Global.model_name in {directory / 'inference.yml'}")
        options[prefix + "_model_dir"] = str(directory)
        options[prefix + "_model_name"] = name
    return options


def serve(connection, config):
    try:
        from paddleocr import PaddleOCR
        model = PaddleOCR(**model_options(config))
        with httpx.Client(timeout=10, follow_redirects=False) as client:
            connection.send({"ready": True})
            while True:
                request = connection.recv()
                if request is None:
                    break
                results = []
                for index, url in enumerate(request["urls"]):
                    started = time.monotonic()
                    logger.info("OCR image started pid=%s index=%s count=%s", os.getpid(), index, len(request["urls"]))
                    try:
                        text = recognize_image(model, client, url, config)
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
    def __init__(self, context, config, service="model-runtime", tracing_settings=None):
        super().__init__(context, config, serve, service, tracing_settings)
