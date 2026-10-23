"""Command line: python3 -m trace_lab.cli {summary,features,run} TRACE_DIR ...  (run from tools/trace-lab). Exit 0 ok, 2 refused trace, 3 user function/script failure."""
import argparse
import json
import sys

from . import decode, features, runner


def _summary(t):
    counts = " ".join(f"{tag}={len(t.arrays[tag]['tMs'])}" for tag in decode.TAGS)
    times = [arr["tMs"] for arr in t.arrays.values() if arr["tMs"]]
    span = (max(x[-1] for x in times) - min(x[0] for x in times)) / 1000.0 if times else 0.0
    why = f" ({', '.join(t.partial_reasons)})" if t.partial_reasons else ""
    return f"trace {t.trace_id} {t.status}{why}\nchunks {t.chunk_count} events {t.event_count} raw {t.raw_bytes} bytes span {span:.1f} s\n{counts}"


def _csv(rows):
    cols = ["second", "tStartMs", "n", "nAccel", *features.FEATURE_NAMES]
    out = [",".join(cols)]
    for r in rows:
        out.append(",".join(repr(r[c]) if isinstance(r[c], float) else str(r[c]) for c in cols))
    return "\n".join(out) + "\n"


def main(argv=None):
    p = argparse.ArgumentParser(prog="trace_lab", description="Decode a pulled trace directory and experiment on it locally.")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("summary", help="verify the trace and print counts"); s.add_argument("trace_dir")
    f = sub.add_parser("features", help="print per-second features as CSV"); f.add_argument("trace_dir"); f.add_argument("--out", help="write the CSV to this file instead of stdout")
    r = sub.add_parser("run", help="run a function from your script over the trace and print the result")
    r.add_argument("trace_dir"); r.add_argument("script"); r.add_argument("--func", default="analyze")
    r.add_argument("--on", choices=("arrays", "features"), default="arrays")
    args = p.parse_args(argv)
    try:
        trace = decode.load_trace(args.trace_dir)
    except decode.TraceError as e:
        print(str(e), file=sys.stderr)
        return 2
    if args.cmd == "summary":
        print(_summary(trace))
    elif args.cmd == "features":
        text = _csv(features.per_second_features(trace))
        if args.out:
            with open(args.out, "w", encoding="utf-8") as fh:
                fh.write(text)
        else:
            sys.stdout.write(text)
    else:
        try:
            result = runner.run_user_function(args.script, args.func, trace, args.on)
        except runner.RunnerError as e:
            print(f"trace-lab: {e}", file=sys.stderr)
            return 3
        try:
            print(json.dumps(result, default=str))
        except (TypeError, ValueError):
            print(repr(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
