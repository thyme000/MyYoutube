import logging
import json
from datetime import datetime, timezone
import os
import re
from logging.handlers import RotatingFileHandler
from pathlib import Path
from model_runtime.context import current, clean


def redact(message):
    message = re.sub(r"https?://[^\s\"<>]+", "[redacted-url]", message)
    message = re.sub(r"(?i)\b(?:bearer)\s+[^\s,;]+", "Bearer [redacted]", message)
    return re.sub(r"(?i)\b(token|password|secret|signature)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", message)


class PrivateFormatter(logging.Formatter):
    def format(self, record):
        message = super().format(record)
        return redact(message)


def fields(record):
    return clean(record.business_context if hasattr(record, "business_context") else current())


class JsonFormatter(PrivateFormatter):
    def __init__(self, service):
        super().__init__()
        self.service = service

    def format(self, record):
        context = fields(record)
        trace = context.pop("traceId", None)
        item = {"@timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
                "service": {"name": self.service}, "log": {"level": record.levelname, "logger": record.name},
                "message": super().format(record), **context}
        if trace:
            item["trace"] = {"id": trace}
        for key in ("workerExecutionSeconds", "workerStartupSeconds", "queueWaitSeconds"):
            if hasattr(record, key):
                item[key] = getattr(record, key)
        return json.dumps(item, ensure_ascii=False, separators=(",", ":"))


class PipeLogHandler(logging.Handler):
    def __init__(self, connection):
        super().__init__()
        self.connection = connection
        self.setFormatter(PrivateFormatter("%(name)s %(message)s"))

    def emit(self, record):
        try:
            item = {"name": record.name, "levelno": record.levelno, "levelname": record.levelname,
                    "msg": self.format(record), "args": (), "created": record.created,
                    "business_context": fields(record)}
            for key in ("workerExecutionSeconds", "workerStartupSeconds"):
                if hasattr(record, key):
                    item[key] = getattr(record, key)
            self.connection.send(item)
        except (BrokenPipeError, EOFError, OSError):
            pass


def configure_logging(service):
    directory = Path(os.getenv("MODEL_LOG_DIRECTORY", "logs"))
    directory.mkdir(parents=True, exist_ok=True)
    formatter = JsonFormatter(service)
    console = logging.StreamHandler()
    file = RotatingFileHandler(directory / f"{service}.log", maxBytes=10 * 1024 * 1024,
                               backupCount=3, encoding="utf-8")
    for handler in (console, file):
        handler.setFormatter(formatter)
    logging.basicConfig(level=logging.INFO, handlers=[console, file], force=True)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
