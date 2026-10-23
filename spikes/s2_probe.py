#!/usr/bin/env python3
"""S2 isolation probe (WP10). Python stdlib only. It is meant to run LATER inside a probe container started with `--network none`, a non-root
`--user`, `--read-only`, in a VM whose only mount is /stage. It refuses to run anywhere else (no /run/.containerenv or /.containerenv), so it can
never probe the Mac by accident. Checks (each a pure function over injected data, so they are unit-tested without any container):
  no_route    no default route, no non-loopback interface, and a short connect to LAN/Mac-looking addresses fails
  rootless    the process is not uid 0 and the container engine reports rootless=1
  read_only_root  / is mounted ro and a write at / fails with EROFS
  no_users    /Users does not exist in the VM and no mount carries /Users
Output: one JSON object (never environment values or file contents), exit 0 only when every check passes."""
import json
import os
import socket
import sys

LAN_TARGETS = [("192.168.0.1", 80), ("192.168.1.1", 80), ("10.0.0.1", 80), ("172.16.0.1", 80), ("169.254.169.254", 80)]
CONTAINERENV = ("/run/.containerenv", "/.containerenv")


def check_no_route(route_text, netdev_text, connect, targets=LAN_TARGETS):
    problems = []
    for line in route_text.splitlines()[1:]:
        f = line.split()
        if len(f) > 1 and f[1] == "00000000":
            problems.append("default route via %s" % f[0])
    for line in netdev_text.splitlines():
        if ":" in line and not line.lstrip().startswith(("Inter", "face")):
            iface = line.split(":", 1)[0].strip()
            if iface and iface != "lo":
                problems.append("interface " + iface)
    for host, port in targets:
        if connect(host, port, 1.0):
            problems.append("reached %s:%d" % (host, port))
    return {"name": "no_route", "ok": not problems, "detail": problems}


def check_rootless(euid, containerenv_text):
    problems = []
    if euid == 0:
        problems.append("running as uid 0")
    if "rootless=1" not in containerenv_text:
        problems.append("engine does not report rootless=1")
    return {"name": "rootless", "ok": not problems, "detail": problems}


def check_read_only_root(try_write, mounts_text):
    problems = []
    ro_line = None
    for line in mounts_text.splitlines():
        f = line.split()
        if len(f) >= 4 and f[1] == "/":
            ro_line = f[3].split(",")  # /proc/mounts: device mountpoint type options dump pass; the last "/" entry wins
    if ro_line is None or "ro" not in ro_line:
        problems.append("/ is not mounted ro")
    try:
        try_write("/pca-probe-write-test")
        problems.append("write at / succeeded")
    except OSError as e:
        if e.errno != 30:
            problems.append("write at / failed with errno %s, not EROFS" % e.errno)
    return {"name": "read_only_root", "ok": not problems, "detail": problems}


def check_no_users(exists, mounts_text):
    problems = []
    if exists("/Users"):
        problems.append("/Users exists")
    for line in mounts_text.splitlines():
        if "/Users" in line.split():
            problems.append("a mount carries /Users")
    return {"name": "no_users", "ok": not problems, "detail": problems}


def _read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def _connect(host, port, timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def _try_write(path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(fd)
    os.remove(path)


def in_container():
    return any(os.path.exists(p) for p in CONTAINERENV)


def main():
    if not in_container():
        sys.stderr.write("s2_probe: refusing to run outside a container (no containerenv); it must never probe the host\n")
        return 2
    mounts = _read("/proc/mounts")
    cenv = _read("/run/.containerenv") or _read("/.containerenv")
    results = [check_no_route(_read("/proc/net/route"), _read("/proc/net/dev"), _connect),
               check_rootless(os.geteuid(), cenv),
               check_read_only_root(_try_write, mounts),
               check_no_users(os.path.exists, mounts)]
    print(json.dumps({"probe": "s2", "ok": all(r["ok"] for r in results), "results": results}))
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
