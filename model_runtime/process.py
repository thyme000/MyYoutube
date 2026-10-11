import os
import signal


class ProcessWorker:
    def __init__(self, context, config, target):
        self.context, self.config, self.target = context, config, target
        self.start()

    def start(self):
        self.connection, child = self.context.Pipe()
        self.process = self.context.Process(target=self.target, args=(child, self.config), daemon=True)
        self.process.start()
        child.close()
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

    def stop(self):
        if self.process.is_alive():
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                self.process.kill()
        self.process.join(timeout=5)
        self.connection.close()

    def run(self, request):
        try:
            self.connection.send(request)
            if not self.connection.poll(self.config["timeout"]):
                raise TimeoutError("Model execution timed out")
            result = self.connection.recv()
        except (TimeoutError, EOFError, BrokenPipeError, OSError):
            self.stop()
            self.start()
            raise RuntimeError("Model worker restarted after execution failure")
        if "error" in result:
            raise RuntimeError(result["error"])
        return result
