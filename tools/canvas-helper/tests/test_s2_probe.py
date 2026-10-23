"""spikes/s2_probe.py assertions, exercised with injected fakes (no network, no container; the probe itself runs later inside a probe container)."""
import importlib.util
import os
import subprocess
import sys

from wp10_env import PROBE

spec = importlib.util.spec_from_file_location("s2_probe", PROBE)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

ROUTE_NONE = "Iface\tDestination\tGateway\tFlags\n"
ROUTE_DEFAULT = ROUTE_NONE + "eth0\t00000000\t0100A8C0\t0003\n"
NET_LO = "Inter-|\n face |\n    lo: 0 0\n"
NET_ETH = NET_LO + "  eth0: 1 1\n"


def conn_refuses(addr, port, timeout):
    return False


def conn_ok(addr, port, timeout):
    return True


def test_no_route_passes_with_no_default_route_and_unreachable_lan():
    assert probe.check_no_route(ROUTE_NONE, NET_LO, conn_refuses)["ok"]


def test_no_route_fails_with_default_route():
    assert not probe.check_no_route(ROUTE_DEFAULT, NET_LO, conn_refuses)["ok"]


def test_no_route_fails_with_non_loopback_interface():
    assert not probe.check_no_route(ROUTE_NONE, NET_ETH, conn_refuses)["ok"]


def test_no_route_fails_when_lan_connect_succeeds():
    assert not probe.check_no_route(ROUTE_NONE, NET_LO, conn_ok)["ok"]


def test_rootless():
    assert probe.check_rootless(1000, "rootless=1\n")["ok"]
    assert not probe.check_rootless(0, "rootless=1\n")["ok"]
    assert not probe.check_rootless(1000, "engine=podman\n")["ok"]


def test_read_only_root():
    def ro(path):
        raise OSError(30, "Read-only file system")

    def rw(path):
        return None

    assert probe.check_read_only_root(ro, "overlay / overlay ro,relatime 0 0\n")["ok"]
    assert not probe.check_read_only_root(rw, "overlay / overlay ro,relatime 0 0\n")["ok"]
    assert not probe.check_read_only_root(ro, "overlay / overlay rw,relatime 0 0\n")["ok"]


def test_no_users_in_vm():
    assert probe.check_no_users(lambda p: False, "tmpfs /tmp tmpfs rw 0 0\n")["ok"]
    assert not probe.check_no_users(lambda p: True, "")["ok"]
    assert not probe.check_no_users(lambda p: False, "mac /Users virtiofs rw 0 0\n")["ok"]


def test_probe_refuses_to_run_outside_a_container():
    r = subprocess.run([sys.executable, PROBE], capture_output=True, text=True, env={"PATH": "/usr/bin:/bin"})
    assert r.returncode == 2 and "container" in r.stderr
