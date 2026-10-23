"""On-disk state under ~/.pca-helper: atomic writes, files 0600, dirs 0700, never state inside a job dir."""
import json
import os
import re
import shutil
from pathlib import Path

from .protocol import constants


class Store:
    def __init__(self, home):
        self.home = Path(home)
        self.stage_dir = self.home / "stage"
        self.journal_dir = self.home / "journal"
        self.artifacts_dir = self.home / "artifacts"
        self.state_path = self.home / "state.json"
        for d in (self.home, self.stage_dir, self.journal_dir, self.artifacts_dir):
            d.mkdir(parents=True, exist_ok=True)
            os.chmod(d, 0o700)

    def write_atomic(self, path, data, mode=0o600):
        path = Path(path)
        tmp = path.with_name(".%s.%d.tmp" % (path.name, os.getpid()))
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(str(tmp), str(path))
        finally:
            if tmp.exists():
                tmp.unlink()
        dfd = os.open(str(path.parent), os.O_RDONLY)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)

    def save_state(self, state):
        self.write_atomic(self.state_path, json.dumps(state, sort_keys=True).encode("utf-8"))

    def load_state(self):
        try:
            with open(self.state_path, encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return None

    @staticmethod
    def _check_job_id(job_id):
        if not isinstance(job_id, str) or re.fullmatch(constants()["jobIdRegex"], job_id) is None or job_id in (".", ".."):
            raise ValueError("bad job id")

    def job_stage_dir(self, job_id):
        self._check_job_id(job_id)
        return self.stage_dir / job_id

    def job_artifact_dir(self, job_id):
        self._check_job_id(job_id)
        return self.artifacts_dir / job_id

    @staticmethod
    def remove_path(p):
        """Remove a file, a symlink (never followed) or a tree. True when nothing is left."""
        p = Path(p)
        try:
            if p.is_symlink() or p.is_file():
                p.unlink()
            elif p.is_dir():
                shutil.rmtree(p)
        except FileNotFoundError:
            pass
        except OSError:
            return False
        return not os.path.lexists(p)

    def delete_job_copies(self, job_id):
        """Stage dir (inputs, export, extraction tree, outputs) and artifacts dir of one job. True when both are gone."""
        a = self.remove_path(self.job_stage_dir(job_id))
        b = self.remove_path(self.job_artifact_dir(job_id))
        return a and b

    def job_dir_names(self):
        names = set()
        for d in (self.stage_dir, self.artifacts_dir):
            for p in d.iterdir():
                if re.fullmatch(constants()["jobIdRegex"], p.name):
                    names.add(p.name)
        return sorted(names)
