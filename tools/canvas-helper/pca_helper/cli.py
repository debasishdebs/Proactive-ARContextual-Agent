"""Command line: `run HOST:PORT FINGERPRINT [CODE|-] [--once]` and `doctor` (stub: runtime, setup and gate checks arrive with WP12/WP13).
`run` pairs and then supervises IN THE SAME PROCESS: the helper session token is memory-only (user ruling), so a helper restart pairs again with
a fresh code; only non-secret pairing metadata (host, port, fingerprint) is written to disk. All output goes through the `out` / `err` streams of
`main` (never a raw print); secrets are never written."""
import argparse
import getpass as _getpass
import os
import sys
from pathlib import Path

from .client import MissingPin, NetworkError, PhoneClient, PinMismatch, TlsTransport, normalize_pin
from .clock import SystemClock
from .expiry import ArtifactExpiry
from .journal import Journal
from .log import Logger
from .pair import PairError, pair
from .store import Store
from .supervisor import RunnerError, Supervisor


class NullRunner:
    """No runtime exists in this build (WP12): a job can only fail `needs_runtime`. There is no host fallback and job code is never executed here."""

    def start(self, job, payload):
        raise RunnerError("needs_runtime")


class _Args(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError(message)


def _default_home(env):
    return Path(env.get("PCA_HELPER_HOME") or (Path(env.get("HOME") or os.path.expanduser("~")) / ".pca-helper"))


def _split_host(hp):
    host, _, port = hp.rpartition(":")
    if not host or not port.isdigit():
        raise ValueError("expected HOST:PORT")
    return host, int(port)


USAGE = "usage: pca-helper run HOST:PORT FINGERPRINT [CODE|-] [--once] | doctor\n"


def _pair_failure(e, err):
    if isinstance(e, MissingPin):
        err.write("give at least 16 hex digits of the certificate fingerprint shown on the phone; nothing was sent\n")
        return 2
    if isinstance(e, PinMismatch):
        err.write("FINGERPRINT MISMATCH: not pairing; nothing was sent\n")
        return 1
    if isinstance(e, PairError):
        err.write("%s\n" % e)
        return 1
    err.write("cannot reach the phone\n")
    return 1


def main(argv=None, out=None, err=None, home=None, transport=None, clock=None, env=None, getpass=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    out = out or sys.stdout
    err = err or sys.stderr
    env = os.environ if env is None else env
    p = _Args(prog="pca-helper")
    sub = p.add_subparsers(dest="cmd")
    sr = sub.add_parser("run")
    sr.add_argument("hostport")
    sr.add_argument("fingerprint")
    sr.add_argument("code", nargs="?")  # omitted or "-": prompted without echo (keeps it out of shell history and ps)
    sr.add_argument("--once", action="store_true")
    sr.add_argument("--name", default="laptop")
    sub.add_parser("doctor")
    try:
        a = p.parse_args(argv)
    except (ValueError, SystemExit):
        err.write(USAGE)
        return 2
    if a.cmd is None:
        err.write(USAGE)
        return 2
    store = Store(home or _default_home(env))
    transport = transport or TlsTransport()
    clock = clock or SystemClock()
    log = Logger(sink=lambda line: err.write(line + "\n"))

    if a.cmd == "run":
        try:
            host, port = _split_host(a.hostport)
        except ValueError:
            err.write("expected HOST:PORT\n")
            return 2
        if normalize_pin(a.fingerprint) is None:  # before any prompt, connection or disk write
            return _pair_failure(MissingPin(), err)
        store.purge_legacy_token()  # an older build persisted the token: it is deleted now, whatever happens next
        code = a.code
        if code is None or code == "-":
            code = (getpass or _getpass.getpass)("pairing code: ")
        try:
            res = pair(store, transport, host, port, code, a.name, a.fingerprint, log)
        except (MissingPin, PinMismatch, PairError, NetworkError) as e:
            return _pair_failure(e, err)
        log.register_secret(res.token)
        st = store.load_state()
        client = PhoneClient(transport, host, port, st["pin"], res.token)
        out.write("paired; py_exec grant %s on the phone\n" % ("is" if res.caps.get("py_exec") else "is not yet"))
        journal = Journal(store.journal_dir, clock)
        sup = Supervisor(client, NullRunner(), journal, store, clock, log, runtime=None, ready=lambda: False, expiry=ArtifactExpiry(journal, clock, log))
        import threading
        try:
            status = sup.run(threading.Event(), max_cycles=1 if a.once else None)
        except PinMismatch:
            err.write("FINGERPRINT MISMATCH: the phone's certificate changed; run again\n")
            return 1
        if status == "session_lost":
            err.write("the phone ended the session: run again with a new code\n")
            return 3
        if a.once and status == "error":
            return 1
        return 0

    # doctor (stub)
    st = store.load_state() or {}
    checks = [
        ("home dir mode 0700", (store.home.stat().st_mode & 0o777) == 0o700),
        ("pairing metadata", bool(st.get("host") and st.get("pin"))),
        ("no persisted session token", "token" not in st),
        ("runtime", False),
        ("python gate stamp", False),
    ]
    notes = {"runtime": " (needs_runtime: runtime checks arrive with the runtime work package)", "python gate stamp": " (no stamp in this build)",
             "pairing metadata": " (informational: every start pairs again)", "no persisted session token": " (run once to delete it)"}
    bad = False
    for name, ok in checks:
        out.write("%s %s%s\n" % ("ok  " if ok else "FAIL", name, "" if ok else notes.get(name, "")))
        bad = bad or not ok
    return 1 if bad else 0
