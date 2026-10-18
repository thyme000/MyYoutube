import threading

import psutil

_peak = 0


def peak_rss_bytes():
    global _peak
    memory = psutil.Process().memory_info()
    _peak = max(_peak, getattr(memory, "peak_wset", memory.rss))
    return _peak


class MemorySampler:
    def __init__(self):
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.sample, daemon=True, name="model-memory")

    def sample(self):
        while not self.stopped.wait(0.1):
            peak_rss_bytes()

    def __enter__(self):
        peak_rss_bytes()
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.stopped.set()
        self.thread.join(timeout=1)


def kill_worker_tree(process):
    try:
        parent = psutil.Process(process.pid)
        parent.suspend()
        children = parent.children(recursive=True)
    except psutil.NoSuchProcess:
        children = []
    try:
        process.kill()
    finally:
        for child in reversed(children):
            try:
                child.kill()
            except psutil.NoSuchProcess:
                pass
        psutil.wait_procs(children, timeout=3)
