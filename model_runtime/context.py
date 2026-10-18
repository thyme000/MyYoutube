import re
from contextlib import contextmanager
from contextvars import ContextVar

HEADERS = {
    "pipelineId": "x-pipeline-id", "contentId": "x-content-id", "taskId": "x-task-id",
    "draftId": "x-draft-id", "preparationId": "x-preparation-id", "mediaVersion": "x-media-version",
    "attempt": "x-task-attempt", "taskType": "x-task-type", "traceId": "x-business-trace-id",
}
_current = ContextVar("model_business_context", default=None)
_identifier = re.compile(r"[A-Za-z0-9_.:-]{1,128}\Z")


def clean(values):
    result = {}
    for key in HEADERS:
        value = values.get(key)
        if value is None:
            continue
        value = str(value)
        if _identifier.fullmatch(value):
            if key == "attempt":
                if value.isascii() and value.isdecimal() and len(value) <= 9:
                    result[key] = int(value)
            else:
                result[key] = value
    return result


def current():
    return dict(_current.get() or {})


@contextmanager
def bind(values):
    token = _current.set(clean(values))
    try:
        yield
    finally:
        _current.reset(token)


class BusinessContextMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = {key.decode("latin1").lower(): value.decode("latin1") for key, value in scope["headers"]}
        values = {} if scope["path"] in ("/health", "/metrics", "/metrics/") else {
            key: headers.get(header) for key, header in HEADERS.items()
        }
        with bind(values):
            await self.app(scope, receive, send)
