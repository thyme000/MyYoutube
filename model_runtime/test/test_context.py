import asyncio
import io
import json
import logging
import multiprocessing
import os
import unittest
import time
from fastapi import FastAPI
import httpx
from model_runtime.context import BusinessContextMiddleware, bind, current
from model_runtime.logs import JsonFormatter
from model_runtime.pool import BoundedPool
from model_runtime.process import ProcessWorker


def context_worker(connection, config):
    connection.send({"ready": True})
    while True:
        request = connection.recv()
        if request is None:
            return
        if request.get("kill"):
            os._exit(9)
        if request.get("timeout"):
            time.sleep(5)
        logging.getLogger("context-worker").warning("worker context", extra={"workerExecutionSeconds": 0.01})
        connection.send({"context": current()})
        logging.getLogger("context-worker").warning("after execution")


class ContextTests(unittest.IsolatedAsyncioTestCase):
    async def test_http_async_thread_ipc_and_parent_log_collection_keep_context(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setFormatter(JsonFormatter("context-test"))
        root = logging.getLogger()
        original, level = root.handlers[:], root.level
        root.handlers, root.level = [handler], logging.INFO
        pool = BoundedPool("context-test", lambda: ProcessWorker(multiprocessing.get_context("spawn"),
                           {"timeout": 2}, context_worker), 1, 4, 2)
        try:
            await pool.start()
            app = FastAPI()
            app.add_middleware(BusinessContextMiddleware)

            @app.post("/work")
            async def work():
                return await pool.run({})

            @app.get("/health")
            async def health():
                return current()

            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
                first, second = await asyncio.gather(
                    client.post("/work", headers={"X-Task-Id": "9007199254740993", "X-Pipeline-Id": "p1",
                                                 "X-Task-Attempt": "4", "X-Business-Trace-Id": "trace-1"}),
                    client.post("/work", headers={"X-Task-Id": "t2", "X-Task-Attempt": "5"}))
                self.assertEqual(first.json()["context"], {"pipelineId": "p1", "taskId": "9007199254740993",
                                                          "attempt": 4, "traceId": "trace-1"})
                self.assertEqual(second.json()["context"], {"taskId": "t2", "attempt": 5})
                self.assertEqual((await client.post("/work")).json()["context"], {})
                self.assertEqual((await client.get("/health", headers={"X-Task-Id": "ignored"})).json(), {})
                self.assertEqual((await client.post("/work", headers={"X-Task-Id": "https://secret", "X-Task-Attempt": "-1"})).json()["context"], {})
        finally:
            await pool.close()
            root.handlers, root.level = original, level
        rows = [json.loads(line) for line in stream.getvalue().splitlines()]
        worker = [row for row in rows if row["message"].endswith("worker context")]
        self.assertEqual([row.get("taskId") for row in worker], ["9007199254740993", "t2", None, None])
        self.assertEqual(worker[0]["trace"]["id"], "trace-1")
        self.assertEqual(worker[0]["workerExecutionSeconds"], 0.01)
        cleared = [row for row in rows if row["message"].endswith("after execution")]
        self.assertTrue(cleared)
        self.assertTrue(all("taskId" not in row for row in cleared))
        self.assertEqual(current(), {})

    async def test_timeout_and_killed_worker_keep_error_context_and_clear_next_job(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setFormatter(JsonFormatter("failure-test"))
        root = logging.getLogger()
        original, level = root.handlers[:], root.level
        root.handlers, root.level = [handler], logging.INFO
        worker = await asyncio.to_thread(ProcessWorker, multiprocessing.get_context("spawn"), {"timeout": 0.1}, context_worker)
        try:
            for request in ({"timeout": True}, {"kill": True}):
                with bind({"taskId": "failed-job", "attempt": 4}):
                    with self.assertRaises(RuntimeError):
                        await asyncio.to_thread(worker.run, request)
                await asyncio.to_thread(worker.recover)
                self.assertEqual((await asyncio.to_thread(worker.run, {}))["context"], {})
        finally:
            await asyncio.to_thread(worker.stop)
            root.handlers, root.level = original, level
        errors = [json.loads(line) for line in stream.getvalue().splitlines() if "connection lost" in line or "timed out" in line]
        self.assertEqual(len(errors), 2)
        self.assertTrue(all(row["taskId"] == "failed-job" and row["attempt"] == 4 for row in errors))
        self.assertEqual(current(), {})


if __name__ == "__main__":
    unittest.main()
