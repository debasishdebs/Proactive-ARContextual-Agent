"""WP12 collector (core plan 4.2 step 3): a tar from `podman volume export` is untrusted DATA; only regular files with sane names come out."""
import io
import os
import stat
import tarfile

import pytest

from pca_helper import collector, profiles

LIM = collector.Limits(max_bytes=1000, max_files=5, max_depth=3, name_max_bytes={"outputs.json": 800})


def make_tar(tmp_path, members, name="x.tar"):
    """members: list of (name, kind, payload) kind in file|dir|sym|hard|fifo|chr|blk|raw(TarInfo)."""
    p = tmp_path / name
    with tarfile.open(p, "w") as t:
        for n, kind, payload in members:
            ti = tarfile.TarInfo(n)
            if kind == "file":
                data = payload if isinstance(payload, bytes) else payload.encode()
                ti.size = len(data)
                t.addfile(ti, io.BytesIO(data))
                continue
            ti.type = {"dir": tarfile.DIRTYPE, "sym": tarfile.SYMTYPE, "hard": tarfile.LNKTYPE, "fifo": tarfile.FIFOTYPE, "chr": tarfile.CHRTYPE,
                       "blk": tarfile.BLKTYPE}[kind]
            if kind in ("sym", "hard"):
                ti.linkname = payload
            t.addfile(ti)
    return p


def refused(tmp_path, members, limits=LIM, code=None):
    p = make_tar(tmp_path, members)
    with pytest.raises(collector.CollectorRefused) as e:
        collector.collect(p, limits)
    if code:
        assert e.value.code == code
    return e.value


def test_regular_files_are_returned_as_data(tmp_path):
    p = make_tar(tmp_path, [("./", "dir", None), ("./outputs.json", "file", '{"a":1}'), ("sub", "dir", None), ("sub/n.txt", "file", "hi")])
    got = collector.collect(p, LIM)
    assert got == {"outputs.json": b'{"a":1}', "sub/n.txt": b"hi"}


def test_dest_gets_regular_0600_files_inside_only(tmp_path):
    p = make_tar(tmp_path, [("outputs.json", "file", "{}"), ("a/b.txt", "file", "x")])
    dest = tmp_path / "extract"
    collector.collect(p, LIM, dest=dest)
    assert sorted(str(f.relative_to(dest)) for f in dest.rglob("*") if f.is_file()) == ["a/b.txt", "outputs.json"]
    assert stat.S_IMODE(os.stat(dest / "outputs.json").st_mode) == 0o600


def test_symlink_refused(tmp_path):
    refused(tmp_path, [("outputs.json", "sym", "/etc/passwd")], code="bad_output")


def test_symlink_component_refused_nothing_written(tmp_path):
    p = make_tar(tmp_path, [("a", "sym", "/etc"), ("a/passwd", "file", "x")])
    dest = tmp_path / "d"
    with pytest.raises(collector.CollectorRefused):
        collector.collect(p, LIM, dest=dest)
    assert not dest.exists() or not any(dest.rglob("*")), "validation is complete before the first byte is written"


@pytest.mark.parametrize("kind", ["hard", "fifo", "chr", "blk"])
def test_special_and_hard_link_members_refused(tmp_path, kind):
    refused(tmp_path, [("n", kind, "outputs.json")], code="bad_output")


@pytest.mark.parametrize("name", ["/abs.txt", "../up.txt", "a/../../up.txt", "a//b.txt", "a/./b.txt", "bad\nname", "tab\tname", "\x7fdel", "a\\b"])
def test_bad_names_refused(tmp_path, name):
    refused(tmp_path, [(name, "file", "x")], code="bad_output")


def test_non_utf8_name_refused(tmp_path):
    p = tmp_path / "n.tar"
    with tarfile.open(p, "w", encoding="latin-1", format=tarfile.GNU_FORMAT) as t:
        ti = tarfile.TarInfo("caf\xe9.txt")
        ti.size = 1
        t.addfile(ti, io.BytesIO(b"x"))
    with pytest.raises(collector.CollectorRefused):
        collector.collect(p, LIM)


def test_duplicate_names_refused(tmp_path):
    refused(tmp_path, [("a.txt", "file", "1"), ("a.txt", "file", "2")], code="bad_output")


def test_depth_limit(tmp_path):
    ok = make_tar(tmp_path, [("a/b/c.txt", "file", "x")])
    assert collector.collect(ok, LIM) == {"a/b/c.txt": b"x"}  # depth 3 = limit
    refused(tmp_path, [("a/b/c/d.txt", "file", "x")], code="bad_output")
    refused(tmp_path, [("a/b/c/d", "dir", None)], code="bad_output")


def test_file_count_limit(tmp_path):
    ok = make_tar(tmp_path, [("f%d" % i, "file", "x") for i in range(5)])
    assert len(collector.collect(ok, LIM)) == 5
    refused(tmp_path, [("f%d" % i, "file", "x") for i in range(6)], code="bad_output")


def test_oversize_per_name_and_total(tmp_path):
    refused(tmp_path, [("outputs.json", "file", b"x" * 801)], code="too_large")
    refused(tmp_path, [("a", "file", b"x" * 600), ("b", "file", b"x" * 600)], code="too_large")
    refused(tmp_path, [("big.bin", "file", b"x" * 1001)], code="too_large")
    ok = make_tar(tmp_path, [("outputs.json", "file", b"x" * 800), ("b", "file", b"x" * 200)])
    assert len(collector.collect(ok, LIM)["outputs.json"]) == 800


def test_bounds_are_checked_from_headers_before_any_read(tmp_path, monkeypatch):
    p = make_tar(tmp_path, [("outputs.json", "file", b"x" * 900)])
    monkeypatch.setattr(tarfile.TarFile, "extractfile", lambda *a, **k: (_ for _ in ()).throw(AssertionError("content was read")))
    with pytest.raises(collector.CollectorRefused):
        collector.collect(p, LIM)


def test_truncated_or_non_tar_input_refused(tmp_path):
    p = make_tar(tmp_path, [("outputs.json", "file", b"x" * 700)])
    cut = tmp_path / "cut.tar"
    cut.write_bytes(p.read_bytes()[:900])
    with pytest.raises(collector.CollectorRefused):
        collector.collect(cut, LIM)
    junk = tmp_path / "junk.tar"
    junk.write_bytes(b"\x1f\x8b" + b"junk" * 300)
    with pytest.raises(collector.CollectorRefused):
        collector.collect(junk, LIM)


def test_limits_come_from_the_py_exec_profile():
    p = profiles.load_table().job_profile("py_exec")
    lim = collector.limits_of(p)
    assert (lim.max_bytes, lim.max_files, lim.max_depth) == (p.out_max_bytes, p.out_max_files, p.out_max_depth) == (33554432, 200, 8)
    assert lim.name_max_bytes == {"outputs.json": 33554432}
