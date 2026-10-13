import logging
import os
import re
from logging.handlers import RotatingFileHandler
from pathlib import Path


class PrivateFormatter(logging.Formatter):
    def format(self, record):
        message = super().format(record)
        return re.sub(r"https?://[^\s\"<>]+", "[redacted-url]", message)


class PipeLogHandler(logging.Handler):
    def __init__(self, connection):
        super().__init__()
        self.connection = connection
        self.setFormatter(PrivateFormatter("%(name)s %(message)s"))

    def emit(self, record):
        try:
            self.connection.send((record.levelno, self.format(record)))
        except (BrokenPipeError, EOFError, OSError):
            pass


def configure_logging(service):
    directory = Path(os.getenv("MODEL_LOG_DIRECTORY", "logs"))
    directory.mkdir(parents=True, exist_ok=True)
    formatter = PrivateFormatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    console = logging.StreamHandler()
    file = RotatingFileHandler(directory / f"{service}.log", maxBytes=10 * 1024 * 1024,
                               backupCount=3, encoding="utf-8")
    for handler in (console, file):
        handler.setFormatter(formatter)
    logging.basicConfig(level=logging.INFO, handlers=[console, file], force=True)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
