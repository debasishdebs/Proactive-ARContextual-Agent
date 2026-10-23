import threading
import time


class SystemClock:
    """now_ms is monotonic (never the wall clock); wall_ms is for persisted expiry metadata only."""

    def now_ms(self):
        return time.monotonic_ns() // 1_000_000

    def wall_ms(self):
        return int(time.time() * 1000)

    def sleep(self, seconds):
        threading.Event().wait(seconds)
