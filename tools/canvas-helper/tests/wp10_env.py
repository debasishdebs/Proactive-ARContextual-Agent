"""Shared fixture for the WP10 tests: a FAKE podman on PATH inside a temp dir. Real podman is never reachable (PATH is the fake dir plus
/usr/bin:/bin, and the fixture asserts that `podman` resolves to the fake)."""
import os
import shutil
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MACHINE_SH = os.path.join(REPO, "container", "vm", "machine.sh")
PROBE = os.path.join(REPO, "spikes", "s2_probe.py")
LOCK_PY = os.path.join(REPO, "tools", "canvas-helper", "pca_lock.py")


class Fake:
    def __init__(self, root):
        self.root = root
        self.bin = os.path.join(root, "bin")
        self.dir = os.path.join(root, "podman-state")
        self.locks = os.path.join(root, "locks")
        self.stage = os.path.join(root, "stage")
        for d in (self.bin, self.dir, self.locks, self.stage):
            os.makedirs(d)
        shutil.copy(os.path.join(HERE, "fakebin", "podman"), os.path.join(self.bin, "podman"))
        os.chmod(os.path.join(self.bin, "podman"), 0o755)

    def set_state(self, state):
        with open(os.path.join(self.dir, "state"), "w") as f:
            f.write(state)

    def state(self):
        with open(os.path.join(self.dir, "state")) as f:
            return f.read().strip()

    def flag(self, name, on=True):
        p = os.path.join(self.dir, name)
        if on:
            open(p, "w").close()
        elif os.path.exists(p):
            os.remove(p)

    def calls(self):
        try:
            with open(os.path.join(self.dir, "calls.log")) as f:
                return [l.rstrip("\n") for l in f]
        except OSError:
            return []

    def env(self):
        e = dict(os.environ)
        e.update(self.patch())
        return e

    def patch(self):
        return {"PATH": self.bin + ":/usr/bin:/bin", "PCA_LOCK_DIR": self.locks, "PCA_FAKE_PODMAN_DIR": self.dir,
                "PCA_PYTHON": sys.executable, "PCA_STAGE_DIR": self.stage, "PCA_MACHINE": "pca", "PCA_POLL_S": "0",
                "PCA_CONFIRM_TRIES": "3", "PCA_STOP_RETRY_S": "0.05"}


@pytest.fixture
def fake(tmp_path, monkeypatch):
    f = Fake(str(tmp_path))
    f.set_state("stopped")
    for k, v in f.patch().items():
        monkeypatch.setenv(k, v)
    assert shutil.which("podman") == os.path.join(f.bin, "podman")
    return f
