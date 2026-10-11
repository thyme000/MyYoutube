import html
import resource
import sys
import json
import os
import signal
import subprocess
import tempfile
import time
import wave
from pathlib import Path

import httpx

from prompt import build_prompt


def timestamp(ms):
    hours, ms = divmod(ms, 3_600_000)
    minutes, ms = divmod(ms, 60_000)
    seconds, ms = divmod(ms, 1000)
    return f"{hours:02}:{minutes:02}:{seconds:02}.{ms:03}"


def write_outputs(segments):
    cues = [f"{timestamp(s['startMs'])} --> {timestamp(s['endMs'])}\n{html.escape(s['text'])}" for s in segments]
    return "WEBVTT\n\n" + "\n\n".join(cues) + "\n", json.dumps({"segments": segments}, ensure_ascii=False)


def serve(connection, config):
    os.setsid()
    client = None
    try:
        from pywhispercpp.model import Model
        import _pywhispercpp as native

        model = Model(config["model"], context_params={"use_gpu": True}, language=config["language"],
                      vad=True, vad_model_path=config["vad"], token_timestamps=True,
                      max_len=config["cue_length"], no_context=True, print_progress=False,
                      print_realtime=False, print_timestamps=False)

        def count(text):
            return abs(native.whisper_tokenize(model._ctx, text, 0, 0)) if text else 0

        if count("Ubuntu") < 1:
            raise RuntimeError("Whisper tokenizer is unavailable")
        client = httpx.Client(timeout=120, follow_redirects=False)
        connection.send({"ready": True, "pid": os.getpid()})
        while True:
            request = connection.recv()
            started = time.monotonic()
            try:
                with tempfile.TemporaryDirectory(prefix="myyoutube-asr-") as directory:
                    source, wav = request["sourceUrl"], Path(directory) / "audio.wav"
                    probe = subprocess.run(["ffprobe", "-v", "error", "-rw_timeout", "30000000", "-select_streams", "a:0",
                                            "-show_entries", "stream=index", "-of", "csv=p=0", source],
                                           capture_output=True, text=True, check=True, timeout=30)
                    prompt = build_prompt(request.get("title"), request.get("tags"), request.get("description"), count)
                    segments, duration_ms = [], 0
                    if probe.stdout.strip():
                        subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-rw_timeout", "30000000", "-i", source,
                                        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(wav)],
                                       capture_output=True, check=True, timeout=120)
                        with wave.open(str(wav)) as audio:
                            duration_ms = round(audio.getnframes() / audio.getframerate() * 1000)
                        raw = model.transcribe(str(wav), initial_prompt=prompt,
                                               language=request.get("language") or config["language"])
                        segments = [{"startMs": max(0, s.t0 * 10), "endMs": min(duration_ms, s.t1 * 10),
                                     "text": s.text.strip()} for s in raw if s.text.strip() and s.t1 > s.t0]
                    vtt, data = write_outputs(segments)
                    for url, content in ((request["vttUploadUrl"], vtt), (request["segmentsUploadUrl"], data)):
                        response = client.put(url, content=content.encode("utf-8"))
                        response.raise_for_status()
                    connection.send({"durationMs": duration_ms, "segmentCount": len(segments), "promptTokenCount": count(prompt),
                                     "speechEmpty": not segments, "elapsedSeconds": time.monotonic() - started,
                                     "workerPid": os.getpid(), "peakRssBytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if sys.platform == "darwin" else 1024)})
            except Exception as error:
                connection.send({"error": type(error).__name__})
    except BaseException as error:
        try:
            connection.send({"error": f"worker initialization failed: {type(error).__name__}"})
        except (BrokenPipeError, EOFError):
            pass
    finally:
        if client is not None:
            client.close()
        connection.close()


from model_runtime.process import ProcessWorker


class Worker(ProcessWorker):
    def __init__(self, context, config):
        super().__init__(context, config, serve)
