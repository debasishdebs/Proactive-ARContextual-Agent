"""Safe output collector (core plan 4.2 step 3). The tar from `podman volume export` is untrusted DATA. Only regular files (and plain directories)
come out; refused: symlinks, hard links, devices, fifos, sparse and any other member type, absolute / `..` / `.` / empty / control-character /
backslash / non-UTF-8 names, duplicates, depth over the limit, more files than the limit, any file above its per-name limit and a total over the
byte limit. Every bound is checked from the HEADERS before a single content byte is read, and nothing is written before the whole archive has been
validated. Returns bytes by relative name (never paths to be re-opened); `dest` additionally gets the files (0600, exclusive create, never
following a link). A refusal carries the fail code the job reports: `too_large` for a size bound, `bad_output` for everything else."""
import os
import tarfile
from dataclasses import dataclass, field
from typing import Dict


class CollectorRefused(Exception):
    def __init__(self, code, why):
        super().__init__(why)
        self.code = code


@dataclass(frozen=True)
class Limits:
    max_bytes: int
    max_files: int
    max_depth: int
    name_max_bytes: Dict[str, int] = field(default_factory=dict)


def limits_of(profile):
    return Limits(profile.out_max_bytes, profile.out_max_files, profile.out_max_depth, dict(profile.out_name_max_bytes))


def _bad(why):
    return CollectorRefused("bad_output", why)


def _clean_name(raw):
    n = raw[2:] if raw.startswith("./") else raw
    if n.endswith("/"):
        n = n[:-1]
    try:
        n.encode("utf-8")  # strict: lone surrogates (undecodable bytes) are refused
    except UnicodeEncodeError:
        raise _bad("name is not utf-8")
    if not n or n.startswith("/") or "\\" in n or any(ord(c) < 32 or ord(c) == 127 for c in n):
        raise _bad("bad name")
    comps = n.split("/")
    if any(c in ("", ".", "..") for c in comps):
        raise _bad("bad path component")
    return n, comps


def _validate(tf, lim):
    files, dirs, seen, total = [], 0, set(), 0
    for m in tf:
        if m.name in (".", "./") and m.type == tarfile.DIRTYPE:
            continue
        if m.type not in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE):
            raise _bad("member type refused")
        name, comps = _clean_name(m.name)
        if len(comps) > lim.max_depth:
            raise _bad("too deep")
        if name in seen:
            raise _bad("duplicate")
        seen.add(name)
        if m.type == tarfile.DIRTYPE:
            dirs += 1
            if dirs > lim.max_files:
                raise _bad("too many entries")
            continue
        if m.size > lim.name_max_bytes.get(name, lim.max_bytes):
            raise CollectorRefused("too_large", "file above its limit")
        total += m.size
        if total > lim.max_bytes:
            raise CollectorRefused("too_large", "total above the limit")
        files.append((name, m))
        if len(files) > lim.max_files:
            raise _bad("too many files")
    return files


def _write(dest, name, data):
    path = os.path.join(dest, *name.split("/"))
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.write(fd, data) if data else None
    finally:
        os.close(fd)


def collect(tar_path, limits, dest=None):
    try:
        with tarfile.open(str(tar_path), mode="r:") as tf:
            files = _validate(tf, limits)
            out = {}
            for name, m in files:
                f = tf.extractfile(m)
                data = f.read(m.size + 1) if f is not None else b""
                if len(data) != m.size:
                    raise _bad("size mismatch")
                out[name] = data
    except CollectorRefused:
        raise
    except (tarfile.TarError, EOFError, OSError, ValueError, UnicodeError):
        raise _bad("not a readable tar")
    if dest is not None:
        os.makedirs(str(dest), mode=0o700, exist_ok=False)
        for name, data in out.items():
            _write(str(dest), name, data)
    return out
