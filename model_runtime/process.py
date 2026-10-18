import logging
import threading
import time
from model_runtime.context import current, bind
from model_runtime import tracing
from model_runtime.resources import MemorySampler, kill_worker_tree

logger = logging.getLogger(__name__)


class WorkerUnavailable(RuntimeError):
    pass


def logged_serve(target, connection, config, logs, service, tracing_settings):
    from model_runtime.logs import PipeLogHandler
    logging.basicConfig(level=logging.INFO, handlers=[PipeLogHandler(logs)], force=True)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    observed = ObservedConnection(connection)
    try:
        tracing.bootstrap(service + ".worker", tracing_settings)
        with MemorySampler():
            target(observed, config)
    finally:
        observed.close()


class ObservedConnection:
    def __init__(self, connection):
        self.connection = connection
        self.context = None
        self.started = None
        self.trace = None
        self.trace_span = None
        self.boot = time.monotonic()

    def recv(self):
        self.clear()
        envelope = self.connection.recv()
        if envelope.get("shutdown"):
            return None
        self.context = bind(envelope["context"])
        self.context.__enter__()
        self.trace = tracing.entry("model/execute", envelope.get("traceCarrier", {}))
        self.trace_span = self.trace.__enter__()
        self.started = time.monotonic()
        return envelope["request"]

    def send(self, result):
        try:
            if self.trace_span is not None and result.get("error"):
                self.trace_span.error_occurred = True
            if self.started is not None:
                logger.info("Model worker execution finished", extra={
                    "workerExecutionSeconds": time.monotonic() - self.started})
            elif result.get("ready"):
                logger.info("Model worker initialized", extra={"business_context": {},
                            "workerStartupSeconds": time.monotonic() - self.boot})
            self.connection.send(result)
        finally:
            self.clear()

    def clear(self):
        if self.trace is not None:
            self.trace.__exit__(None, None, None)
            self.trace = None
            self.trace_span = None
        if self.context is not None:
            self.context.__exit__(None, None, None)
            self.context = None
        self.started = None

    def close(self):
        self.clear()
        self.connection.close()


class ProcessWorker:
    def __init__(self, context, config, target, service="model-runtime", tracing_settings=None):
        self.context, self.config, self.target = context, config, target
        self.service = service
        self.tracing_settings = tracing_settings
        self.timeout = config["timeout"] if isinstance(config, dict) else config.timeout
        self.shutdown_requested = threading.Event()
        self.start()

    def start(self):
        started = time.monotonic()
        self.ready = False
        if not logging.getLogger().handlers:
            from model_runtime.logs import configure_logging
            configure_logging(self.service)
        self.logs, child_logs = self.context.Pipe(duplex=False)
        self.log_stop = threading.Event()
        self.connection, child = self.context.Pipe()
        self.process = self.context.Process(target=logged_serve, args=(self.target, child, self.config, child_logs, self.service, self.tracing_settings), daemon=True)
        self.log_thread = None
        try:
            self.process.start()
        except BaseException:
            child.close()
            child_logs.close()
            self.connection.close()
            self.logs.close()
            raise
        child.close()
        child_logs.close()
        self.log_thread = threading.Thread(target=self.collect_logs, daemon=True)
        self.log_thread.start()
        deadline = time.monotonic() + 120
        while not self.connection.poll(0.1):
            if self.shutdown_requested.is_set() or time.monotonic() >= deadline:
                self.stop()
                raise RuntimeError("Model worker startup cancelled or timed out")
        try:
            response = self.connection.recv()
        except (EOFError, OSError) as error:
            self.stop()
            raise RuntimeError("Model worker failed during startup") from error
        if not response.get("ready"):
            self.stop()
            raise RuntimeError(response.get("error", "Model worker failed"))
        if self.shutdown_requested.is_set():
            self.stop()
            raise RuntimeError("Model worker startup cancelled")
        self.ready = True
        logger.info("Model worker ready pid=%s", self.process.pid, extra={
            "workerStartupSeconds": time.monotonic() - started})

    def stop(self):
        self.ready = False
        if self.process.is_alive():
            try:
                self.connection.send({"shutdown": True})
                self.process.join(timeout=0.5)
            except (BrokenPipeError, EOFError, OSError):
                pass
            if self.process.is_alive():
                kill_worker_tree(self.process)
        if self.process.pid is not None:
            self.process.join(timeout=5)
        self.connection.close()
        if self.log_thread is not None:
            self.log_thread.join(timeout=1)
            self.log_stop.set()
            self.log_thread.join(timeout=1)
        self.logs.close()

    def request_stop(self):
        self.shutdown_requested.set()

    def recover(self):
        self.stop()
        if self.shutdown_requested.is_set():
            raise RuntimeError("Model worker is shutting down")
        self.start()

    def collect_logs(self):
        try:
            while not self.log_stop.is_set():
                if self.logs.poll(0.1):
                    record = logging.makeLogRecord(self.logs.recv())
                    logging.getLogger(record.name).handle(record)
        except (EOFError, OSError):
            pass

    def run(self, request):
        if not self.ready or not self.process.is_alive():
            self.ready = False
            raise WorkerUnavailable("Model worker is not ready")
        try:
            with tracing.ipc_exit() as carrier:
                self.connection.send({"request": request, "context": current(), "traceCarrier": carrier})
                if not self.connection.poll(self.timeout):
                    raise TimeoutError("Model execution timed out")
                result = self.connection.recv()
        except (TimeoutError, EOFError, BrokenPipeError, OSError) as error:
            self.ready = False
            self.process.join(timeout=0.1)
            reason = (f"Model execution timed out after {self.timeout}s" if isinstance(error, TimeoutError)
                      else f"Model worker connection lost (exitcode={self.process.exitcode})")
            logger.error("%s pid=%s items=%s", reason, self.process.pid, len(request.get("urls", [])))
            raise RuntimeError(reason) from error
        if "error" in result:
            raise RuntimeError(result["error"])
        return result
