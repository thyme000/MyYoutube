from dataclasses import dataclass
from pathlib import Path
import os
import logging

from dotenv import dotenv_values
from pydantic import BaseModel, ConfigDict, Field


@dataclass(frozen=True)
class ServiceIdentity:
    kind: str
    name: str
    default_port: int


SERVICES = {kind: ServiceIdentity(kind, f"{kind}-svc", port) for kind, port in (("asr", 8089), ("ocr", 8088))}
ENV_PREFIXES = ("ASR_", "OCR_", "NACOS_", "MODEL_", "PIPELINE_TRACING_", "SW_")


class Settings(BaseModel):
    model_config = ConfigDict(frozen=True, allow_inf_nan=False, populate_by_name=True)


class DiscoverySettings(Settings):
    enabled: bool = Field(default=True, alias="NACOS_ENABLED")
    address: str = Field(default="127.0.0.1:8848", alias="NACOS_ADDR", min_length=1)
    instance_ip: str = Field(default="127.0.0.1", alias="NACOS_INSTANCE_IP", min_length=1)
    namespace: str = Field(default="", alias="NACOS_NAMESPACE")
    group: str = Field(default="DEFAULT_GROUP", alias="NACOS_GROUP", min_length=1)
    cluster: str = Field(default="DEFAULT", alias="NACOS_CLUSTER", min_length=1)
    username: str = Field(default="", alias="NACOS_USERNAME", repr=False)
    password: str = Field(default="", alias="NACOS_PASSWORD", repr=False)
    interval: float = Field(default=15, alias="NACOS_CHECK_INTERVAL", gt=0)
    timeout: float = Field(default=10, alias="NACOS_OPERATION_TIMEOUT", gt=0)
    max_delay: float = Field(default=60, alias="NACOS_RECONNECT_MAX_DELAY", gt=0)


class TracingSettings(Settings):
    enabled: bool = Field(default=False, alias="PIPELINE_TRACING_ENABLED")
    collector: str = Field(default="127.0.0.1:11800", alias="SW_AGENT_COLLECTOR_BACKEND_SERVICES", min_length=1)
    samples: int = Field(default=10, alias="MODEL_TRACE_SAMPLE_PER_3_SECS", ge=0)


class PoolSettings(Settings):
    concurrency: int = Field(default=1, ge=1)
    queue_size: int = Field(default=4, ge=0)
    queue_timeout: float = Field(default=60, gt=0)
    recovery_attempts: int = Field(default=3, ge=1, le=10)
    recovery_backoff: float = Field(default=1, gt=0, le=60)
    recovery_max_delay: float = Field(default=30, gt=0, le=300)


class AsrSettings(Settings):
    model: Path
    vad: Path
    language: str = Field(default="zh", min_length=1)
    cue_length: int = Field(default=32, ge=1)
    timeout: float = Field(default=1200, gt=0)


class OcrSettings(Settings):
    detection: Path
    recognition: Path
    textline_orientation: Path | None = None
    doc_orientation: Path | None = None
    doc_unwarping: Path | None = None
    recognition_batch_size: int = Field(default=2, ge=1)
    timeout: float = Field(default=120, gt=0)
    max_image_bytes: int = Field(default=33554432, ge=1)
    max_image_pixels: int = Field(default=40000000, ge=1)
    max_image_edge: int = Field(default=16000, ge=1)


class RuntimeSettings(Settings):
    directory: Path
    port: int = Field(ge=1, le=65535)
    log_directory: Path
    pool: PoolSettings
    engine: AsrSettings | OcrSettings
    discovery: DiscoverySettings
    tracing: TracingSettings


def service_directory(identity, directory=None):
    return Path(directory).resolve() if directory else Path(__file__).resolve().parents[1] / identity.name


def _path(values, name, directory, model_directory=False):
    value = (values.get(name) or "").strip()
    if not value:
        raise ValueError(f"{name} must be explicitly set in {directory / '.env'}")
    path = Path(value).expanduser()
    path = (directory / path).resolve() if not path.is_absolute() else path.resolve()
    if model_directory:
        required = [path / "inference.yml", path / "inference.pdiparams"]
        graph = next((path / filename for filename in ("inference.json", "inference.pdmodel") if (path / filename).is_file()), None)
        if graph is None:
            raise ValueError(f"{name} is missing inference.json or inference.pdmodel: {path}")
        required.append(graph)
    else:
        required = [path]
    for file in required:
        if not file.is_file() or file.stat().st_size == 0:
            raise ValueError(f"{name} is missing a nonempty model file: {file}")
        with file.open("rb") as opened:
            opened.read(1)
    if model_directory:
        import yaml
        with required[0].open(encoding="utf-8") as metadata:
            content = yaml.safe_load(metadata)
        global_settings = content.get("Global") if isinstance(content, dict) else None
        name_value = global_settings.get("model_name") if isinstance(global_settings, dict) else None
        if not isinstance(name_value, str) or not name_value.strip():
            raise ValueError(f"{name} is missing Global.model_name in {required[0]}")
    return path


def _boolean(values, name, default):
    value = str(values.get(name, str(default))).lower()
    if value not in ("true", "false"):
        raise ValueError(f"{name} must be true or false")
    return value == "true"


def load_settings(identity, directory=None):
    directory = service_directory(identity, directory)
    file = directory / ".env"
    if not file.is_file():
        raise ValueError(f"Missing service configuration: {file}")
    values = dict(dotenv_values(file, interpolate=False))
    prefix = identity.kind.upper()
    pool = PoolSettings(**{field: values.get(f"{prefix}_{field.upper()}", default)
                           for field, default in (("concurrency", 1), ("queue_size", 4), ("queue_timeout", 60),
                                                  ("recovery_attempts", 3), ("recovery_backoff", 1),
                                                  ("recovery_max_delay", 30))})
    if identity.kind == "asr":
        engine = AsrSettings(model=_path(values, "ASR_MODEL", directory), vad=_path(values, "ASR_VAD_MODEL", directory),
                             language=values.get("ASR_LANGUAGE", "zh"), cue_length=values.get("ASR_CUE_LENGTH", 32),
                             timeout=values.get("ASR_EXECUTION_TIMEOUT", 1200))
    else:
        optional = {}
        for field, flag, key, default in (
                ("textline_orientation", "OCR_USE_TEXTLINE_ORIENTATION", "OCR_TEXTLINE_ORIENTATION_MODEL_DIR", True),
                ("doc_orientation", "OCR_USE_DOC_ORIENTATION", "OCR_DOC_ORIENTATION_MODEL_DIR", False),
                ("doc_unwarping", "OCR_USE_DOC_UNWARPING", "OCR_DOC_UNWARPING_MODEL_DIR", False)):
            optional[field] = _path(values, key, directory, True) if _boolean(values, flag, default) else None
        engine = OcrSettings(detection=_path(values, "OCR_DETECTION_MODEL_DIR", directory, True),
                             recognition=_path(values, "OCR_RECOGNITION_MODEL_DIR", directory, True), **optional,
                             recognition_batch_size=values.get("OCR_RECOGNITION_BATCH_SIZE", 2),
                             timeout=values.get("OCR_EXECUTION_TIMEOUT", 120),
                             **{field: values.get(f"OCR_{field.upper()}", default) for field, default in (
                                 ("max_image_bytes", 33554432), ("max_image_pixels", 40000000), ("max_image_edge", 16000))})
    log_directory = Path(values.get("MODEL_LOG_DIRECTORY") or "logs")
    if not log_directory.is_absolute():
        log_directory = directory / log_directory
    settings = RuntimeSettings(directory=directory, port=values.get("SERVER_PORT", identity.default_port),
                               log_directory=log_directory.resolve(), pool=pool, engine=engine,
                               discovery=DiscoverySettings.model_validate({field.alias: values[field.alias]
                                   for field in DiscoverySettings.model_fields.values() if field.alias in values}),
                               tracing=TracingSettings.model_validate({field.alias: values[field.alias]
                                   for field in TracingSettings.model_fields.values() if field.alias in values}))
    # Shared SDK/tracing helpers also receive only this service's file configuration.
    ignored = sorted(key for key, value in os.environ.items()
                     if (key.startswith(ENV_PREFIXES) or key == "SERVER_PORT") and values.get(key) != value)
    if ignored:
        logging.getLogger(__name__).warning("Ignoring parent configuration keys=%s; configure %s instead", ignored, file)
    for key in list(os.environ):
        if key.startswith(ENV_PREFIXES) or key == "SERVER_PORT":
            os.environ.pop(key)
    os.environ.update({key: value for key, value in values.items()
                       if value is not None and (key.startswith(ENV_PREFIXES) or key == "SERVER_PORT")})
    os.environ["SERVER_PORT"] = str(settings.port)
    os.environ["MODEL_LOG_DIRECTORY"] = str(settings.log_directory)
    return settings
