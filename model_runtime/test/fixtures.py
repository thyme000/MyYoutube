from contextlib import contextmanager
import logging
from pathlib import Path


@contextmanager
def isolated_logging():
    root = logging.getLogger()
    original_handlers = root.handlers
    loggers = (root, logging.getLogger('httpx'), logging.getLogger('httpcore'))
    levels = [logger.level for logger in loggers]
    # configure_logging uses force=True, which closes all attached root handlers.
    root.handlers = []
    try:
        yield
    finally:
        for handler in root.handlers[:]:
            root.removeHandler(handler)
            handler.close()
        root.handlers = original_handlers
        for logger, level in zip(loggers, levels):
            logger.setLevel(level)


def service_env(directory, kind="asr", extra=""):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if kind == "asr":
        (directory / "speech.bin").write_bytes(b"test model")
        (directory / "vad.bin").write_bytes(b"test model")
        models = "ASR_MODEL=speech.bin\nASR_VAD_MODEL=vad.bin\n"
    else:
        for name in ("det", "rec", "orientation"):
            model = directory / name
            model.mkdir(exist_ok=True)
            (model / "inference.yml").write_text(f"Global:\n  model_name: test_{name}\n")
            (model / "inference.json").write_text("{}")
            (model / "inference.pdiparams").write_bytes(b"test weights")
        models = "OCR_DETECTION_MODEL_DIR=det\nOCR_RECOGNITION_MODEL_DIR=rec\nOCR_TEXTLINE_ORIENTATION_MODEL_DIR=orientation\n"
    (directory / ".env").write_text(models + "NACOS_ENABLED=false\n" + extra, encoding="utf-8")
