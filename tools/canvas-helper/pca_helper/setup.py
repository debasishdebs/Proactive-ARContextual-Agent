"""`setup` (part-C H9): builds the argv of the online setup steps. A DRY RUN unless `--apply`. It writes ~/.pca-helper/config.json (the one place the
runtime path is recorded; this is the only place a podman path is ever supplied) and builds the Python job image through the named connection. It never
creates, starts, stops or removes the machine: that is `container/vm/machine.sh` inside a `pca_lock.py vm-session` owner (core plan 8.1)."""
import json
import os
from dataclasses import dataclass
from typing import List, Optional

from .runtime import PodmanRuntime

IMAGE_CONTEXT = "container/pyjob"


@dataclass
class Step:
    name: str
    argv: Optional[List[str]]
    note: str = ""


def machine_init_argv(table, stage_dir):
    """What `machine.sh recreate` runs (reference and test pin): ONE explicit staging volume, never $HOME."""
    m = table.machine
    return ["machine", "init", "--cpus", str(m["cpus"]), "--memory", str(m["memoryMb"]), "--disk-size", str(m["diskGb"]),
            "--volume", "%s:/stage" % stage_dir, m["name"]]


def plan(table, home, podman, repo):
    s = table.profiles["setup"]
    conn = table.machine["name"]
    image = table.job_profile("py_exec").image
    build = [podman, "--connection", conn, "build", "--memory", "%dm" % s.memory_mb, "--memory-swap", "%dm" % s.memory_mb, "--cpus", str(s.cpus),
             "-t", image, "-f", os.path.join(repo, IMAGE_CONTEXT, "Containerfile"), os.path.join(repo, IMAGE_CONTEXT)]
    return [Step("config", None, "write %s" % os.path.join(str(home), "config.json")),
            Step("machine", None, "run container/vm/machine.sh recreate inside `pca_lock.py vm-session` (the VM lifecycle is never done here)"),
            Step("image_pyjob", build, "online: base image pull; user present; no job code, no key")]


def main(store, out, table, podman, apply, repo, log=None):
    if not isinstance(podman, str) or not os.path.isabs(podman):
        out.write("give the absolute path of podman with --podman (no PATH lookup)\n")
        return 2
    steps = plan(table, store.home, podman, repo)
    for st in steps:
        out.write("%s: %s\n" % (st.name, " ".join(st.argv) if st.argv else st.note))
    if not apply:
        out.write("dry run: nothing was changed; add --apply to write the config and build the image\n")
        return 0
    cfg = {"runtime": {"podman": podman, "connection": table.machine["name"], "machine": table.machine["name"]}}
    store.write_atomic(store.home / "config.json", json.dumps(cfg, sort_keys=True).encode("utf-8"))
    rt = PodmanRuntime(podman, table.machine["name"], table.machine["name"], None, log)
    for st in steps:
        if st.argv:
            if rt.execute(st.argv[3:]) != 0:  # argv = podman --connection <name> <args>
                out.write("step %s failed\n" % st.name)
                return 1
    out.write("setup done\n")
    return 0
