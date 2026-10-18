from pathlib import Path


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
