import asyncio
import multiprocessing
import os
import threading
import time
import unittest
from unittest.mock import patch
import app
from engine import Worker, write_outputs
from prompt import build_prompt


def idle_worker(connection, config):
    os.setsid()
    connection.send({"ready": True})
    connection.recv()
    time.sleep(60)


class PromptTests(unittest.TestCase):
    def test_priority_and_tail(self):
        result = build_prompt("Ubuntu", "Linux", "开头。" + "填充。" * 200 + "结尾Kubernetes。", len, 100)
        self.assertLessEqual(len(result), 100)
        self.assertTrue(result.startswith("Ubuntu\nLinux"))
        self.assertIn("结尾Kubernetes", result)

    def test_title_is_also_bounded(self):
        result = build_prompt("标题" * 500, "标签", "简介", len, 224)
        self.assertEqual(len(result), 224)
        self.assertNotIn("简介", result)

    def test_empty_and_escaped_cues(self):
        vtt, segments = write_outputs([])
        self.assertTrue(vtt.startswith("WEBVTT\n"))
        self.assertEqual(segments, '{"segments": []}')
        vtt, _ = write_outputs([{"startMs": 1234, "endMs": 2345, "text": "<字幕>"}])
        self.assertIn("00:00:01.234 --> 00:00:02.345", vtt)
        self.assertIn("&lt;字幕&gt;", vtt)

    def test_timeout_replaces_process(self):
        with patch("engine.serve", idle_worker):
            worker = Worker(multiprocessing.get_context("spawn"), {"timeout": 0.1})
            old = worker.process
            try:
                with self.assertRaises(RuntimeError): worker.run({})
                self.assertFalse(old.is_alive())
                self.assertNotEqual(old.pid, worker.process.pid)
                self.assertTrue(worker.process.is_alive())
            finally: worker.stop()


class QueueTests(unittest.IsolatedAsyncioTestCase):
    async def test_admission_is_bounded_and_workers_overlap(self):
        release, lock = threading.Event(), threading.Lock()
        running, peak = 0, 0
        class FakeWorker:
            def run(self, request):
                nonlocal running, peak
                with lock:
                    running += 1
                    peak = max(peak, running)
                release.wait(5)
                with lock: running -= 1
                return {"segmentCount": 0}
        from model_runtime.pool import BoundedPool
        FakeWorker.stop = lambda self: None
        app.app.state.pool = BoundedPool("asr-test", FakeWorker, app.CONCURRENCY, app.QUEUE_SIZE, 60)
        await app.app.state.pool.start()
        request = app.AsrRequest(sourceUrl="http://localhost/input", vttUploadUrl="http://localhost/vtt", segmentsUploadUrl="http://localhost/json")
        jobs = [asyncio.create_task(app.transcribe(request)) for _ in range(app.CONCURRENCY + app.QUEUE_SIZE)]
        await asyncio.sleep(0.1)
        try:
            with self.assertRaises(app.HTTPException) as error: await app.transcribe(request)
            self.assertEqual(error.exception.status_code, 503)
            self.assertEqual(peak, app.CONCURRENCY)
        finally:
            release.set()
            await asyncio.gather(*jobs)
        self.assertEqual(app.app.state.pool.admitted, 0)


if __name__ == "__main__": unittest.main()
