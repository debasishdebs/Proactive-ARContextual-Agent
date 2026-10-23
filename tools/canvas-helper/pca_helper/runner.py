"""The ONE runner argv builder (core plan section 2) and the runner that plugs into the supervisor (class `JobRunner`, bottom of the file).

`build_argv` is the only place a `podman run` argument list is assembled, for every row of the profile table; `verify_argv` re-checks the finished
list (flags present, nothing privileged or host-wide, every bind source under /stage/<jobId>/, exactly one named volume pca-job-<jobId>:/out)
before anything is executed. A job id that podman cannot use in a container or volume name is refused.
"""
import os
import re

from . import profiles

STAGE_ROOT = "/stage"  # the machine's ONE mount (the VM-side path of $HOME/.pca-helper/stage)
NAME_PREFIX = "pca-job-"
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")  # the colon of the protocol id regex is not valid in a podman volume name

# One list of flags every container row gets; the argv test and `verify_argv` both read it.
BASE_FLAGS = ["--rm", "--read-only", "--cap-drop=ALL", "--security-opt", "no-new-privileges", "--pull", "never"]
REQUIRED = ("--rm", "--read-only", "--cap-drop=ALL", "--security-opt", "--user", "--pids-limit", "--memory", "--memory-swap", "--cpus",
            "--tmpfs", "--timeout", "--network", "--name")
FORBIDDEN_FLAGS = frozenset(("--privileged", "--net", "--pid", "--userns", "--ipc", "--uts", "--device", "--cap-add", "--env", "-e", "--env-file",
                             "--group-add", "--mount", "--volume", "--volumes-from", "--add-host", "--cgroupns", "--sysctl", "--entrypoint",
                             "--security-opt=", "--network=", "--user=", "--security-opts"))


class ArgvRefused(Exception):
    pass


def safe_job_id(job_id):
    if not isinstance(job_id, str) or SAFE_ID.fullmatch(job_id) is None:
        raise ArgvRefused("job id cannot name a container")
    return job_id


def volume_name(job_id):
    return NAME_PREFIX + safe_job_id(job_id)


def stage_dir(job_id):
    return "%s/%s" % (STAGE_ROOT, safe_job_id(job_id))


def build_argv(profile, name, *, binds=(), volume=None, timeout_s, labels=None, command=(), image=None):
    """`binds`: (source, target, "ro") tuples (VM paths); `volume`: (name, target) of the one rw named volume or None. Returns the args after `podman`."""
    img = image or profile.image
    if not img:
        raise ArgvRefused("profile has no image")
    a = ["run"] + list(BASE_FLAGS)
    a += ["--name", name]
    for k, v in sorted((labels or {}).items()):
        a += ["--label", "%s=%s" % (k, v)]
    a += ["--user", profile.user, "--pids-limit", str(profile.pids_limit), "--memory", "%dm" % profile.memory_mb,
          "--memory-swap", "%dm" % profile.memory_swap_mb, "--cpus", str(profile.cpus),
          "--tmpfs", "/tmp:size=%dm,nosuid%s" % (profile.tmpfs_mb, ",noexec" if profile.tmpfs_noexec else ""),
          "--network", profile.network, "--log-driver", "k8s-file", "--log-opt", "max-size=%d" % profile.log_max_bytes,
          "--timeout", str(max(int(timeout_s), 1))]
    for src, dst, mode in binds:
        a += ["-v", "%s:%s:%s" % (src, dst, mode)]
    if volume is not None:
        a += ["-v", "%s:%s:rw" % volume]
    a.append(img)
    a += list(command)
    return a


def job_timeout_s(profile, remaining_ms):
    """min(profile deadline, remaining job deadline) + 2 s; the helper's own kill is the primary bound, this one outlives a dead helper."""
    return min(profile.deadline_s, max(int(remaining_ms), 0) // 1000) + 2


def build_job_argv(job_id, *, remaining_ms, table=None):
    t = table or profiles.load_table()
    p = t.job_profile("py_exec")
    name = volume_name(job_id)
    sd = stage_dir(job_id)
    a = build_argv(p, name, binds=[(sd + "/in", "/in", "ro")], volume=(name, "/out"), timeout_s=job_timeout_s(p, remaining_ms),
                   labels={"pca.helper": "1", "pca.job": job_id})
    verify_argv(a, job_id)
    return a


def verify_argv(a, job_id):
    """Refuse any argv that is not exactly a sane job run. Raises ArgvRefused."""
    safe_job_id(job_id)
    if not a or a[0] != "run":
        raise ArgvRefused("not a run")
    for r in REQUIRED:
        if a.count(r) != 1:
            raise ArgvRefused("missing or repeated " + r)
    if "no-new-privileges" not in a or any(t.startswith("seccomp=") or t.startswith("apparmor=") for t in a):
        raise ArgvRefused("security-opt")
    binds, vols, i = [], [], 1
    while i < len(a):
        t = a[i]
        if t == "-v":
            if i + 1 >= len(a):
                raise ArgvRefused("dangling -v")
            parts = a[i + 1].split(":")
            if len(parts) != 3:
                raise ArgvRefused("mount syntax")
            (binds if parts[0].startswith("/") else vols).append(tuple(parts))
            i += 2
            continue
        if t.split("=")[0] in FORBIDDEN_FLAGS or t.startswith(("--network=", "--security-opt=", "--user=")):
            raise ArgvRefused("forbidden " + t.split("=")[0])
        i += 1
    prefix = "%s/%s/" % (STAGE_ROOT, job_id)
    for src, dst, mode in binds:
        parts = src.split("/")
        if not src.startswith(prefix) or ".." in parts or "." in parts or "" in parts[1:] or "ro" not in mode.split(","):
            raise ArgvRefused("bind source outside the job subtree or not read-only")
    if vols != [(NAME_PREFIX + job_id, "/out", "rw")]:
        raise ArgvRefused("the only named volume is pca-job-<jobId>:/out")
    if a[a.index("--network") + 1] in ("host", "bridge", "slirp4netns", "pasta") or a[a.index("--user") + 1] in ("0", "root", ""):
        raise ArgvRefused("network or user")
