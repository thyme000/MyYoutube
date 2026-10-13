import os
import signal
import logging
import threading

logger = logging.getLogger(__name__)


def logged_serve(target, connection, config, logs):
    from model_runtime.logs import PipeLogHandler
    logging.basicConfig(level=logging.INFO, handlers=[PipeLogHandler(logs)], force=True)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    target(connection, config)


class ProcessWorker:
    def __init__(self, context, config, target):
        self.context, self.config, self.target = context, config, target
        self.start()

    def start(self):
        self.ready = False
        if not logging.getLogger().handlers:
            from model_runtime.logs import configure_logging
            configure_logging("model-runtime")
        self.logs, child_logs = self.context.Pipe(duplex=False)
        self.log_stop = threading.Event()
        self.connection, child = self.context.Pipe()
        self.process = self.context.Process(target=logged_serve, args=(self.target, child, self.config, child_logs), daemon=True)
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
        if not self.connection.poll(120):
            self.stop()
            raise RuntimeError("Model worker startup timed out")
        try:
            response = self.connection.recv()
        except (EOFError, OSError) as error:
            self.stop()
            raise RuntimeError("Model worker failed during startup") from error
        if not response.get("ready"):
            self.stop()
            raise RuntimeError(response.get("error", "Model worker failed"))
        self.ready = True
        logger.info("Model worker ready pid=%s", self.process.pid)

    def stop(self):
        self.ready = False
        if self.process.is_alive():
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                self.process.kill()
        if self.process.pid is not None:
            self.process.join(timeout=5)
        self.connection.close()
        if self.log_thread is not None:
            self.log_thread.join(timeout=1)
            self.log_stop.set()
            self.log_thread.join(timeout=1)
        self.logs.close()

    def collect_logs(self):
        try:
            while not self.log_stop.is_set():
                if self.logs.poll(0.1):
                    level, message = self.logs.recv()
                    logger.log(level, "%s", message)
        except (EOFError, OSError):
            pass

    def run(self, request):
        if not self.ready or not self.process.is_alive():
            self.stop()
            self.start()
        try:
            self.connection.send(request)
            if not self.connection.poll(self.config["timeout"]):
                raise TimeoutError("Model execution timed out")
            result = self.connection.recv()
        except (TimeoutError, EOFError, BrokenPipeError, OSError) as error:
            self.ready = False
            self.process.join(timeout=0.1)
            reason = (f"Model execution timed out after {self.config['timeout']}s" if isinstance(error, TimeoutError)
                      else f"Model worker connection lost (exitcode={self.process.exitcode})")
            logger.error("%s pid=%s items=%s", reason, self.process.pid, len(request.get("urls", [])))
            self.stop()
            try:
                self.start()
            except Exception as restart_error:
                logger.exception("Model worker restart failed")
                raise RuntimeError(f"{reason}; worker restart failed: {restart_error}") from error
            raise RuntimeError(f"{reason}; worker restarted after execution failure") from error
        if "error" in result:
            raise RuntimeError(result["error"])
        return result
