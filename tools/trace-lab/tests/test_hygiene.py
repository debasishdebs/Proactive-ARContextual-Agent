import ast
import re
from pathlib import Path
from trace_lab import decode

ROOT = Path(__file__).resolve().parent.parent
PKG = ROOT / "trace_lab"
FORBIDDEN = {"socket", "ssl", "http", "urllib", "urllib3", "requests", "httpx", "ftplib", "smtplib", "telnetlib", "xmlrpc", "asyncio",
             "sqlite3", "dbm", "shelve", "psycopg2", "subprocess", "multiprocessing", "webbrowser", "pickle", "ctypes"}


def _imports(path):
    for n in ast.walk(ast.parse(path.read_text())):
        if isinstance(n, ast.Import):
            for a in n.names:
                yield a.name.split(".")[0]
        elif isinstance(n, ast.ImportFrom) and n.module and n.level == 0:
            yield n.module.split(".")[0]


def test_no_network_no_db_no_writes():
    files = sorted(PKG.glob("*.py"))
    assert {f.name for f in files} >= {"decode.py", "features.py", "runner.py", "cli.py"}
    for f in files:
        assert not (set(_imports(f)) & FORBIDDEN), f.name
        src = f.read_text()
        if f.name != "cli.py":  # the only module that may write is cli.py, and only the user-named --out file
            assert not re.search(r"""open\([^)]*['"][wax+]""", src) and "write_text" not in src and "write_bytes" not in src and "os.remove" not in src, f.name
    cli_src = (PKG / "cli.py").read_text()
    assert cli_src.count("open(") == 1 and "args.out" in cli_src
    assert sys_stdlib_only(files)


def sys_stdlib_only(files):
    import sys
    allowed = set(sys.stdlib_module_names) | {"trace_lab"}
    return all(m in allowed for f in files for m in _imports(f))


def test_readme_format_matches_decoder():
    text = (ROOT / "README.md").read_text()
    block = text.split("<!-- tags:begin -->")[1].split("<!-- tags:end -->")[0]
    rows = re.findall(r"^\| `([a-z]+)` \| (.*?) \|$", block, re.M)
    tags = [r[0] for r in rows]
    assert tags == list(decode.TAGS) == "g,a,cx,rs,pf,gp,pl,ep,sc,gt,fl,bo,bl,dk,pr,cl".split(",")
    for tag, fields in rows:
        listed = [f.strip("` ") for f in fields.split(",") if f.strip()] if fields.strip() != "(none)" else []
        assert listed == list(decode.FIELDS[tag]), tag
    assert decode.CHECKSUM_RULE in text
    assert "PCT1" in text and "delete" in text.lower()
