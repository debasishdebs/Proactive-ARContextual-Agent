"""WP12 seam: the CLI plugs the runtime and the runner into the WP11 supervisor, and offers doctor and setup."""
import io
import json

from fakes import FakeTransport
from pca_helper.cli import main
from test_protocol_and_cli import FP16, PAIR_OK, POLL_OK
from wp12_env import env, fake  # noqa: F401


def run_once(env):
    tr = FakeTransport("ab" * 32)
    tr.route("POST", "/api/helper/pair", PAIR_OK)
    tr.route("POST", "/api/helper/poll", POLL_OK)
    out, err = io.StringIO(), io.StringIO()
    code = main(["run", "h:1", FP16, "654321", "--once"], out=out, err=err, home=env.home, transport=tr)
    poll = [x for x in tr.sent if x["path"].endswith("/poll")][0]
    return code, json.loads(poll["body"])["ready"], out.getvalue() + err.getvalue()


def stamp(env):
    (env.home / "gates").mkdir(exist_ok=True)
    (env.home / "gates" / "python.stamp").write_text("x")


def test_ready_only_with_runtime_stamp_and_running_machine(env):
    env.write_config()
    assert run_once(env)[1] == {"py_exec": False}, "no gate stamp yet"
    stamp(env)
    code, ready, out = run_once(env)
    assert code == 0 and ready == {"py_exec": True}
    assert "654321" not in out
    env.fake.set_state("stopped")
    assert run_once(env)[1] == {"py_exec": False}, "VM stopped: not ready (never started from here)"
    assert not any(c.startswith("machine start") for c in env.fake.calls())


def test_unusable_runtime_never_advertises_ready_and_runs_nothing(env):
    env.write_config(machine="other")
    stamp(env)
    code, ready, _ = run_once(env)
    assert code == 0 and ready == {"py_exec": False}
    assert not any(c.startswith(("run ", "volume create")) for c in env.fake.calls())


def test_doctor_and_setup_dispatch(env):
    out, err = io.StringIO(), io.StringIO()
    assert main(["doctor"], out=out, err=err, home=env.home) == 1 and "FAIL runtime" in out.getvalue()
    out = io.StringIO()
    assert main(["setup", "--podman", env.podman], out=out, err=err, home=env.home) == 0
    assert "dry run" in out.getvalue() and not (env.home / "config.json").exists()
    assert main(["setup"], out=io.StringIO(), err=err, home=env.home) == 2, "podman path is required: no PATH lookup"
