"""Shared fixture for the WP12 tests: the FAKE podman of wp10_env plus a helper home, a runtime config and scenario switches. Real podman is
never reachable (PATH is the fake dir plus /usr/bin:/bin)."""
import json
import os
import stat

import pytest

from wp10_env import fake  # noqa: F401
from fakes import FakeClock
from pca_helper import profiles
from pca_helper.journal import Journal
from pca_helper.log import Logger
from pca_helper.runtime import PodmanRuntime
from pca_helper.store import Store


class Env:
    def __init__(self, fake, tmp_path):
        self.fake = fake
        self.home = tmp_path / "home"
        self.store = Store(self.home)
        self.clock = FakeClock()
        self.lines = []
        self.log = Logger(sink=self.lines.append)
        self.journal = Journal(self.store.journal_dir, self.clock)
        self.podman = os.path.join(fake.bin, "podman")
        self.table = profiles.load_table()
        self.runtime = PodmanRuntime(self.podman, "pca", "pca", self.journal, self.log)

    # scenario switches of the fake podman
    def plan(self, **p):
        with open(os.path.join(self.fake.dir, "run_plan.json"), "w") as f:
            json.dump(p, f)

    def volumes(self):
        d = os.path.join(self.fake.dir, "volumes")
        return sorted(n for n in os.listdir(d) if not n.endswith(".rawtar")) if os.path.isdir(d) else []

    def containers(self):
        d = os.path.join(self.fake.dir, "containers")
        return sorted(os.listdir(d)) if os.path.isdir(d) else []

    def runs(self):
        try:
            with open(os.path.join(self.fake.dir, "runs.jsonl")) as f:
                return [json.loads(l) for l in f]
        except OSError:
            return []

    def write_config(self, **over):
        cfg = {"runtime": {"podman": self.podman, "connection": "pca", "machine": "pca"}}
        cfg["runtime"].update(over)
        (self.home / "config.json").write_text(json.dumps(cfg))


@pytest.fixture
def env(fake, tmp_path):
    fake.set_state("running")
    return Env(fake, tmp_path)


def make_decoy(path, marker):
    path.write_text("#!/bin/sh\necho ran >> %s\nexit 0\n" % marker)
    path.chmod(path.stat().st_mode | stat.S_IXUSR)
