import io
import os
import httpx
from model_runtime.process import ProcessWorker


def serve(connection, config):
    os.setsid()
    try:
        import numpy as np
        from PIL import Image
        from paddleocr import PaddleOCR
        model = PaddleOCR(lang="ch")
        with httpx.Client(timeout=10, follow_redirects=False) as client:
            connection.send({"ready": True})
            while True:
                request = connection.recv()
                results = []
                for url in request["urls"]:
                    try:
                        response = client.get(url)
                        response.raise_for_status()
                        image = np.asarray(Image.open(io.BytesIO(response.content)).convert("RGB"))
                        pages = model.predict(image)
                        text = "\n".join(t for page in pages for t in page["rec_texts"])
                        results.append({"url": url, "text": text})
                    except Exception as error:
                        results.append({"url": url, "error": type(error).__name__})
                connection.send({"results": results})
    except BaseException as error:
        try:
            connection.send({"error": type(error).__name__})
        except (BrokenPipeError, EOFError):
            pass
    finally:
        connection.close()


class Worker(ProcessWorker):
    def __init__(self, context, config):
        super().__init__(context, config, serve)
