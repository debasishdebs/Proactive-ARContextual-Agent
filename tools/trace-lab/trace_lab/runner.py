"""Runs a user-supplied Python function over a decoded trace, in this process (threat model: your own code on your own laptop; no sandbox)."""
import importlib.util
from pathlib import Path

from .features import per_second_features


class RunnerError(Exception):
    """The user's script could not be loaded or the function failed; carries a short fixed-form text (exception TYPE only, never data)."""


def run_user_function(script, func, trace, on="arrays"):
    """Load `script` (a .py file), call its `func`: with the Trace (on="arrays") or with the per-second feature rows (on="features")."""
    if on not in ("arrays", "features"):
        raise RunnerError("bad --on value")
    try:
        spec = importlib.util.spec_from_file_location("trace_lab_user_script", str(Path(script)))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
    except Exception as e:  # the user's own code
        raise RunnerError(f"script failed to load ({type(e).__name__})") from None
    fn = getattr(mod, func, None)
    if not callable(fn):
        raise RunnerError(f"function '{func}' not found in the script")
    try:
        return fn(trace if on == "arrays" else per_second_features(trace))
    except Exception as e:
        raise RunnerError(f"function failed ({type(e).__name__})") from None
