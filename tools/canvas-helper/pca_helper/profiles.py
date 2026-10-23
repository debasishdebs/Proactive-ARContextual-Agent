"""The ONE profile table (core plan section 2): rows are data read from config/resources.json. Exactly one JOB row exists in CORE (`py_exec`);
`setup` and `probe` are non-job rows of the same table and can never run a leased job. Unknown rows are refused. No number lives in code."""
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict

DEFAULT_PATH = Path(__file__).resolve().parent.parent / "config" / "resources.json"


class ProfileError(Exception):
    pass


@dataclass(frozen=True)
class Profile:
    name: str
    job: bool
    image: object
    network: str
    user: str
    memory_mb: int
    memory_swap_mb: int
    cpus: float
    pids_limit: int
    tmpfs_mb: int
    tmpfs_noexec: bool
    log_max_bytes: int
    deadline_s: int
    out_max_bytes: int
    out_max_files: int
    out_max_depth: int
    out_name_max_bytes: Dict[str, int] = field(default_factory=dict)
    per_item_s: int = 0


@dataclass(frozen=True)
class Table:
    profiles: Dict[str, Profile]
    machine: dict
    host: dict

    def get(self, name):
        p = self.profiles.get(name) if isinstance(name, str) else None
        if p is None:
            raise ProfileError("unknown profile")
        return p

    def job_profile(self, name):
        p = self.get(name)
        if not p.job:
            raise ProfileError("not a job profile")
        return p


def _profile(name, c):
    try:
        return Profile(name=name, job=bool(c["job"]), image=c.get("image"), network=str(c["network"]), user=str(c["user"]),
                       memory_mb=int(c["memoryMb"]), memory_swap_mb=int(c["memorySwapMb"]), cpus=c["cpus"], pids_limit=int(c["pidsLimit"]),
                       tmpfs_mb=int(c["tmpfsMb"]), tmpfs_noexec=bool(c["tmpfsNoexec"]), log_max_bytes=int(c["logMaxBytes"]),
                       deadline_s=int(c["deadlineS"]), out_max_bytes=int(c["outMaxBytes"]), out_max_files=int(c["outMaxFiles"]),
                       out_max_depth=int(c["outMaxDepth"]), out_name_max_bytes=dict(c.get("outNameMaxBytes") or {}), per_item_s=int(c.get("perItemS", 0)))
    except (KeyError, TypeError, ValueError):
        raise ProfileError("bad profile row")


def _checked(p):
    if p.memory_swap_mb != p.memory_mb:  # memory = swap: no swap, whatever the table says
        raise ProfileError("memory-swap must equal memory")
    return p


def load_table(path=None):
    try:
        with open(path or DEFAULT_PATH, encoding="utf-8") as f:
            raw = json.load(f)
        rows = {n: _checked(_profile(n, c)) for n, c in raw["profiles"].items()}
        return Table(rows, dict(raw["machine"]), dict(raw["host"]))
    except (OSError, ValueError, KeyError, TypeError):
        raise ProfileError("resource table unreadable")
