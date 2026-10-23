"""The `python` gate stamp (`~/.pca-helper/gates/python.stamp`; core plan WP13). It is written ONLY by the live gate script (`gates/py_gate_live.sh`)
and only when its results file says every required check passed; `doctor` and the poll `ready` flag read its existence (`doctor.gate_ready`).
`clear` removes it (the gate script clears it first, so a failed re-run never leaves an old stamp standing). The stamp holds no secret."""
import json
import os
import sys
import time

from . import doctor
from .store import Store

REQUIRED = ("image", "user_10001", "root_readonly", "network_none", "out_writable", "round_trip", "float_result", "per_item_timeout_kill",
            "exception", "schema_mismatch", "too_big", "oom", "ids_once", "volume_gone", "stage_gone", "kill_within_5s")


class StampRefused(Exception):
    pass


def clear(store):
    try:
        os.unlink(str(doctor.gate_stamp_path(store)))
    except FileNotFoundError:
        pass


def write_stamp(store, results, commit="", image_id=""):
    """`results`: {check name: bool}. Raises StampRefused unless every REQUIRED check is exactly True."""
    if not isinstance(results, dict):
        raise StampRefused("results")
    missing = [n for n in REQUIRED if results.get(n) is not True]
    if missing:
        raise StampRefused("checks not passed: %s" % ",".join(missing))
    path = doctor.gate_stamp_path(store)
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(str(path.parent), 0o700)
    body = {"gate": "python", "at": int(time.time()), "commit": str(commit)[:64], "image": str(image_id)[:128], "checks": sorted(REQUIRED)}
    store.write_atomic(path, json.dumps(body, sort_keys=True).encode("utf-8"))
    return path


def main(argv, out, err, home=None):
    import argparse
    p = argparse.ArgumentParser(prog="pca_helper.gate_stamp")
    p.add_argument("action", choices=("write", "clear"))
    p.add_argument("--results", default="")
    p.add_argument("--commit", default="")
    p.add_argument("--image-id", default="")
    try:
        a = p.parse_args(argv)
    except SystemExit:
        return 2
    store = Store(home or os.environ.get("PCA_HELPER_HOME") or os.path.join(os.path.expanduser("~"), ".pca-helper"))
    if a.action == "clear":
        clear(store)
        return 0
    try:
        with open(a.results, encoding="utf-8") as f:
            results = json.load(f)
        path = write_stamp(store, results, a.commit, a.image_id)
    except (OSError, ValueError, StampRefused) as e:
        err.write("no stamp written: %s\n" % (e if isinstance(e, StampRefused) else "results unreadable"))
        return 1
    out.write("stamp written: %s\n" % path)
    return 0


if __name__ == "__main__":  # the process's own file descriptors (the logger and cli own the sys streams)
    sys.exit(main(sys.argv[1:], os.fdopen(1, "w", closefd=False), os.fdopen(2, "w", closefd=False)))
