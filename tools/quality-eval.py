#!/usr/bin/env python3
"""Read-only quality evaluation of the Proactive Context phone app through the canvas LAN server (python3 stdlib only).

Pair first with  tools/m1-run.sh pair <host:port> <code> <fp8>: this tool reuses its state ($TMPDIR/m1-run-<uid>/{host,auth.hdr,cert.der}).
TLS is pinned on the certificate m1-run.sh stored at pairing (the peer certificate must be byte-identical), never on the system trust store.
It never prints the session token, the pairing code or any secret; memory text appears only as 80-char snippets and only with --samples N.

Sub-commands: capture | retrieval | canvas | output | all   (see tools/QUALITY-EVAL.md)
Safety: GET only, plus POSTs on an explicit read-only allowlist (empty by default); every other verb or path is refused before a byte is sent.
"""
import argparse, collections, json, math, os, random, re, ssl, sys, time, http.client, urllib.parse

# ---------------------------------------------------------------- safety + transport

class Refused(Exception):
    pass

ALLOWED_METHODS = ("GET", "POST")
# POST routes that only compute. Empty by default: no shipped route scores an arbitrary query without a model call.
# --retrieve-route adds exactly one path (it must look like a retrieval debug route).
READ_ONLY_POSTS = set()
DESTRUCTIVE_HINTS = ("delete", "rollback", "publish", "freeze", "save", "cancel", "start", "stop", "import", "replay", "rebuild",
                     "scores", "enabled", "capability", "debug-shadow", "restore", "use-settings", "logout", "pair", "derived")


def guard(method, path):
    """Raises Refused for anything that could mutate. Called for every request before the socket is touched."""
    m = method.upper()
    if m not in ALLOWED_METHODS:
        raise Refused("verb %s is never sent" % m)
    if not path.startswith("/api/"):
        raise Refused("path outside /api")
    base = path.split("?", 1)[0]
    if m == "POST":
        if base not in READ_ONLY_POSTS:
            raise Refused("POST %s is not on the read-only allowlist" % base)
        if any(h in base.lower() for h in DESTRUCTIVE_HINTS):
            raise Refused("POST %s looks like a mutation" % base)


def state_dir():
    t = os.environ.get("TMPDIR") or "/tmp"
    return t.rstrip("/") + "/m1-run-%d" % os.getuid()


class Client:
    def __init__(self, rate=5.0, timeout=60):
        st = state_dir()
        try:
            self.host = open(os.path.join(st, "host")).read().strip()
            hdr = open(os.path.join(st, "auth.hdr")).read().strip()
        except OSError:
            raise SystemExit("not paired: run  tools/m1-run.sh pair <host:port> <code> <fp8>")
        if not hdr.lower().startswith("authorization:"):
            raise SystemExit("auth header file is malformed: pair again")
        self._auth = hdr.split(":", 1)[1].strip()
        self.scheme = os.environ.get("M1_SCHEME", "https")
        self.cert = None
        if self.scheme == "https":
            try:
                self.cert = open(os.path.join(st, "cert.der"), "rb").read()
            except OSError:
                raise SystemExit("no pinned certificate: pair again with tools/m1-run.sh")
        self.min_gap = 1.0 / max(0.1, min(5.0, rate))
        self.timeout = timeout
        self.last = 0.0
        self.log = []          # (method, path-without-query, status) of every request sent
        self.unavailable = {}  # path template -> reason

    def _conn(self):
        h, _, p = self.host.rpartition(":")
        if self.scheme == "http":
            return http.client.HTTPConnection(h, int(p), timeout=self.timeout)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # replaced by the exact-certificate pin below
        c = http.client.HTTPSConnection(h, int(p), timeout=self.timeout, context=ctx)
        c.connect()
        if c.sock.getpeercert(binary_form=True) != self.cert:
            c.close()
            raise SystemExit("CERTIFICATE MISMATCH: the server is not the paired phone; nothing was sent")
        return c

    def call(self, method, path, body=None, tag=None):
        """Returns (status, parsed JSON or None). Status 0 = transport error. Never raises for HTTP errors."""
        guard(method, path)
        wait = self.min_gap - (time.monotonic() - self.last)
        if wait > 0:
            time.sleep(wait)
        self.last = time.monotonic()
        data = json.dumps(body).encode() if body is not None else None
        hdrs = {"Authorization": self._auth, "Accept": "application/json"}
        if data is not None:
            hdrs["Content-Type"] = "application/json"
        st, obj = 0, None
        try:
            c = self._conn()
            c.request(method.upper(), path, body=data, headers=hdrs)
            r = c.getresponse()
            raw = r.read()
            st = r.status
            c.close()
            try:
                obj = json.loads(raw.decode("utf-8")) if raw else None
            except Exception:
                obj = None
        except SystemExit:
            raise
        except Exception as e:
            st = 0
            obj = {"error": type(e).__name__}
        self.log.append((method.upper(), path.split("?", 1)[0], st))
        if st != 200:
            key = tag or path.split("?", 1)[0]
            err = obj.get("error") if isinstance(obj, dict) else None
            self.unavailable.setdefault(key, "http=%s%s" % (st, " error=%s" % err if err else ""))
        return st, obj

    def get(self, path, tag=None):
        st, obj = self.call("GET", path, tag=tag)
        return obj if st == 200 else None

# ---------------------------------------------------------------- stats helpers

def quartiles(xs):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    def q(p):
        k = (len(xs) - 1) * p
        f = math.floor(k); c = math.ceil(k)
        return xs[f] if f == c else xs[f] + (xs[c] - xs[f]) * (k - f)
    return {"n": len(xs), "min": xs[0], "q1": q(.25), "median": q(.5), "q3": q(.75), "max": xs[-1]}


def fq(qs, fmt="%.1f"):
    if not qs:
        return "n/a"
    return "n=%d min=%s q1=%s median=%s q3=%s max=%s" % (qs["n"], *[(fmt % qs[k]) for k in ("min", "q1", "median", "q3", "max")])


def pct(a, b):
    return "n/a" if not b else "%.1f %%" % (100.0 * a / b)


def share(a, b):
    return (a / b) if b else 0.0


def snip(t):
    t = re.sub(r"\s+", " ", str(t or "")).strip()
    return (t[:77] + "...") if len(t) > 80 else t


STOP = set("""a an the and or but if then else of to in on at by for with from into onto over under about as is are was were be been being am do does did
doing have has had having this that these those it its it's i me my we our you your he she him her they them their there here what which who whom whose
when where why how all any both each few more most other some such no nor not only own same so than too very can will just should now also up down out
off again further once can't cannot don't isn't wasn't aren't image shows showing shown appears appear seems seem photo picture visible view scene
there's one two while near next behind front left right top bottom some""".split())
VERBISH = set("is are was were be has have had looking holding sitting standing walking using wearing showing appears seems".split())
REFUSAL = ("i cannot", "i can't", "i'm unable", "i am unable", "image shows", "the image", "there is no", "there are no", "as an ai", "unable to determine",
           "not possible to", "no visible", "i don't see", "cannot be determined")
IDK = ("i don't know", "i do not know", "not sure", "no memories", "couldn't find", "could not find", "i don't have", "no information", "don't remember", "nothing about")
NEGATIVE_QUERIES = ["submarine periscope maintenance schedule", "penguin colony antarctic expedition", "volcano eruption lava sample",
                    "saxophone recital concert hall", "camel caravan sahara desert", "space shuttle launch countdown", "glacier crevasse climbing rope",
                    "coral reef scuba certification", "harpsichord tuning workshop", "rodeo bull riding arena", "zeppelin airship hangar", "sumo wrestling tournament"]


def toks(t):
    return re.findall(r"[a-z0-9]+(?:'[a-z]+)?", str(t or "").lower())


def content(t):
    return [w for w in toks(t) if w not in STOP and (len(w) > 2 or w.isdigit())]


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / float(len(a | b))


def day_of(ts):
    return time.strftime("%Y-%m-%d", time.localtime(ts / 1000.0))

# ---------------------------------------------------------------- data collection

def fetch_frames(cl, max_frames):
    frames, seen, before = [], set(), None
    while len(frames) < max_frames:
        q = "/api/frames?limit=120" + ("&before=%d" % before if before is not None else "")
        page = cl.get(q, tag="/api/frames")
        if not isinstance(page, list) or not page:
            break
        new = [f for f in page if isinstance(f, dict) and f.get("id") not in seen]
        for f in new:
            seen.add(f.get("id")); frames.append(f)
        oldest = min((f.get("ts") or 0) for f in page)
        if not new or (before is not None and oldest >= before):
            break
        before = oldest
    return frames[:max_frames]


def fetch_memories(cl, frames, max_detail):
    """GET /api/frames/:id for frames with live memories, evenly spread over time; returns (memories, details-by-frame)."""
    withm = [f for f in frames if f.get("hasLiveMemories")]
    if len(withm) > max_detail:
        step = len(withm) / float(max_detail)
        withm = [withm[int(i * step)] for i in range(max_detail)]
    mems, det = [], {}
    for f in withm:
        d = cl.get("/api/frames/%s" % urllib.parse.quote(str(f.get("id")), safe=""), tag="/api/frames/:id")
        if not isinstance(d, dict):
            continue
        det[f["id"]] = d
        for m in d.get("live") or []:
            if isinstance(m, dict) and m.get("id"):
                mems.append({"id": m["id"], "text": m.get("text") or "", "type": m.get("type"), "frameId": f["id"], "ts": f.get("ts") or 0,
                             "state": f.get("state")})
    return mems, det


class Ctx:
    def __init__(self, cl, a):
        self.cl, self.a = cl, a
        self._frames = self._mems = None

    def frames(self):
        if self._frames is None:
            self._frames = fetch_frames(self.cl, self.a.max_frames)
        return self._frames

    def mems(self):
        if self._mems is None:
            self._mems, _ = fetch_memories(self.cl, self.frames(), self.a.max_detail)
        return self._mems

# ---------------------------------------------------------------- 1. capture

STATE_MAP = {"EXTRACTED": "OK", "CLUSTERED": "OK", "IN_CLUSTER": "OK", "NEAR_DUP": "rejected (near-dup)", "FAILED": "failed",
             "FAILED_FINAL": "failed", "PENDING": "pending", "EXTRACTING": "pending", "CAPTION_QUEUED": "pending"}


def eval_capture(ctx, out):
    a, cl = ctx.a, ctx.cl
    R = {"area": "capture", "lines": [], "m": {}}
    L = R["lines"]
    fr = ctx.frames()
    m = R["m"]
    m["frames"] = len(fr)
    L.append("Routes: GET /api/frames (paged, limit 120), GET /api/frames/:id (memory text, sampled), GET /api/moments?day=, GET /api/scenes.")
    if "/api/frames" in cl.unavailable and not fr:
        L.append("- frames: **unavailable** (%s)" % cl.unavailable["/api/frames"]); return R
    L.append("- frames fetched: %d (cap --max-frames %d)" % (len(fr), a.max_frames))
    ts = sorted(f.get("ts") or 0 for f in fr)
    if not ts:
        return R
    days = collections.Counter(day_of(t) for t in ts)
    hours = collections.Counter(time.strftime("%Y-%m-%d %H", time.localtime(t / 1000.0)) for t in ts)
    L.append("- days covered: %d; frames per day %s" % (len(days), fq(quartiles(days.values()), "%.0f")))
    L.append("- frames per active hour: %s" % fq(quartiles(hours.values()), "%.0f"))
    iv = [(ts[i + 1] - ts[i]) / 1000.0 for i in range(len(ts) - 1) if day_of(ts[i]) == day_of(ts[i + 1])]
    L.append("- inter-frame interval s (same day): %s" % fq(quartiles(iv)))
    if iv:
        L.append("  - < 2 s: %s; 2-30 s: %s; 30 s-10 min: %s; > 10 min: %s" % tuple(
            pct(sum(1 for x in iv if lo <= x < hi), len(iv)) for lo, hi in ((0, 2), (2, 30), (30, 600), (600, 1e18))))
    blur = [f.get("blur") for f in fr if isinstance(f.get("blur"), (int, float))]
    L.append("- blur (frame meta): %s" % (fq(quartiles(blur), "%.3f") if blur else "unavailable (no blur values)"))
    L.append("- photo byte size: unavailable (no metadata route exposes it; photos are never downloaded by this tool)")
    dwell = sum(1 for f in fr if f.get("dwell"))
    L.append("- dwell frames: %d (%s)" % (dwell, pct(dwell, len(fr))))
    nomem = sum(1 for f in fr if not f.get("hasLiveMemories"))
    m["no_memory_share"] = share(nomem, len(fr))
    L.append("- frames with no live memory: %d (%s)" % (nomem, pct(nomem, len(fr))))
    sc = collections.Counter(str(f.get("state")) for f in fr)
    oc = collections.Counter(STATE_MAP.get(s, "unknown/none") for s in sc.elements())
    L.append("- extraction outcome (frame state): " + ", ".join("%s %d (%s)" % (k, v, pct(v, len(fr))) for k, v in oc.most_common()))
    L.append("  - raw states: " + ", ".join("%s=%d" % kv for kv in sc.most_common()))
    L.append("  - needs-review: unavailable on frames (counted per run in the Canvas section); confidence: unavailable (not exposed)")
    m["failed_share"] = share(oc.get("failed", 0), len(fr))
    m["rejected_share"] = share(oc.get("rejected (near-dup)", 0), len(fr))
    now = time.time() * 1000
    fut = sum(1 for t in ts if t > now + 5 * 60e3)
    old = sum(1 for t in ts if t < now - 365 * 86400e3 or t < 1577836800000)
    m["bad_ts_share"] = share(fut + old, len(ts))
    L.append("- timestamps: future (> now+5 min) %d, very old (> 1 y or before 2020) %d; glasses-to-phone clock offset: unavailable (frames carry one ts)" % (fut, old))
    src = collections.Counter(str(f.get("source")) for f in ctx.frames() if "source" in f)
    if src:
        L.append("- sources: " + ", ".join("%s=%d" % kv for kv in src.most_common()))
    # memories
    ms = ctx.mems()
    L.append("- memories read: %d from %d frames (cap --max-detail %d)" % (len(ms), len({x['frameId'] for x in ms}), a.max_detail))
    m["memories"] = len(ms)
    if ms:
        lens = [len(x["text"]) for x in ms]
        L.append("- memory length chars: %s" % fq(quartiles(lens), "%.0f"))
        empty = [x for x in ms if not x["text"].strip()]
        short = [x for x in ms if 0 < len(x["text"].strip()) < 20]
        long_ = [x for x in ms if len(x["text"]) > 600]
        L.append("- empty %d (%s), very short < 20 chars %d (%s), very long > 600 chars %d (%s)" % (
            len(empty), pct(len(empty), len(ms)), len(short), pct(len(short), len(ms)), len(long_), pct(len(long_), len(ms))))
        m["empty_short_share"] = share(len(empty) + len(short), len(ms))
        types = collections.Counter(str(x["type"]) for x in ms)
        L.append("- memory types: " + ", ".join("%s=%d" % kv for kv in types.most_common()))
        norm = collections.Counter(" ".join(toks(x["text"])) for x in ms if x["text"].strip())
        exact = sum(c - 1 for c in norm.values() if c > 1)
        sets = [(x, set(content(x["text"]))) for x in ms if len(content(x["text"])) >= 3]
        near, pairs = set(), []
        for i in range(len(sets)):
            for j in range(i + 1, len(sets)):
                if jaccard(sets[i][1], sets[j][1]) >= a.jaccard:
                    near.add(sets[j][0]["id"]); pairs.append((sets[i][0], sets[j][0]))
        m["dup_share"] = share(max(exact, len(near)), len(ms))
        L.append("- duplicate captions: exact (normalised) %d (%s); near-duplicate (content-word Jaccard >= %.2f) %d memories (%s)" % (
            exact, pct(exact, len(ms)), a.jaccard, len(near), pct(len(near), len(ms))))
        ref = [x for x in ms if any(p in x["text"].lower() for p in REFUSAL)]
        m["refusal_share"] = share(len(ref), len(ms))
        rc = collections.Counter(p for x in ms for p in REFUSAL if p in x["text"].lower())
        L.append("- refusal/boilerplate phrases: %d memories (%s)%s" % (len(ref), pct(len(ref), len(ms)),
                 ("; " + ", ".join("'%s'=%d" % kv for kv in rc.most_common(6))) if rc else ""))
        if a.samples:
            for title, xs in (("near-duplicate pairs", [(snip(p[0]["text"]), snip(p[1]["text"])) for p in pairs]),
                              ("refusal/boilerplate", [snip(x["text"]) for x in ref]), ("very short", [snip(x["text"]) for x in short])):
                if xs:
                    L.append("  - samples, %s:" % title)
                    for s in xs[:a.samples]:
                        L.append("    - " + " ~ ".join("`%s`" % t.replace("`", "'") for t in (s if isinstance(s, tuple) else (s,))))
    # moments + scenes
    mo, mo_ok = [], True
    for d in sorted(days)[-a.max_days:]:
        page = cl.get("/api/moments?limit=120&day=%s" % d, tag="/api/moments")
        if page is None:
            mo_ok = False; break
        mo.extend(x for x in page if isinstance(x, dict))
    if mo_ok:
        typ = collections.Counter(str(x.get("type")) for x in mo)
        photoless = sum(1 for x in mo if not x.get("photos"))
        err = sum(1 for x in mo if x.get("errorReason"))
        nomem = sum(1 for x in mo if not x.get("memories"))
        L.append("- Moments (last %d days, <= 120 per day): %d; types %s; photo-less %d (%s); with errorReason %d; without memory %d (%s)" % (
            min(len(days), a.max_days), len(mo), dict(typ), photoless, pct(photoless, len(mo)), err, nomem, pct(nomem, len(mo))))
        m["moments"] = len(mo)
    else:
        L.append("- Moments: **unavailable** (%s)" % cl.unavailable.get("/api/moments"))
    scn = cl.get("/api/scenes?limit=120", tag="/api/scenes")
    if isinstance(scn, list):
        dur = [((s.get("endMs") or 0) - (s.get("startMs") or 0)) / 1000.0 for s in scn if isinstance(s, dict)]
        nm = [len(s.get("momentIds") or []) for s in scn if isinstance(s, dict)]
        L.append("- Scenes (first 120): %d; duration s %s; Moments per Scene %s" % (len(scn), fq(quartiles(dur)), fq(quartiles(nm), "%.0f")))
    else:
        L.append("- Scenes: **unavailable** (%s)" % cl.unavailable.get("/api/scenes"))
    return R

# ---------------------------------------------------------------- 2. retrieval

class LocalBM25:
    name = "LOCAL-BM25 (reference baseline computed by this tool, NOT the phone's retriever)"

    def __init__(self, mems):
        self.docs = [(x["id"], content(x["text"])) for x in mems]
        self.df = collections.Counter(w for _, d in self.docs for w in set(d))
        self.N = len(self.docs) or 1
        self.avg = (sum(len(d) for _, d in self.docs) / float(self.N)) or 1.0

    def idf(self, w):
        n = self.df.get(w, 0)
        return math.log(1 + (self.N - n + .5) / (n + .5))

    def search(self, q, k=10):
        qs = content(q)
        out = []
        for i, d in self.docs:
            if not d:
                continue
            tf = collections.Counter(d)
            s = sum(self.idf(w) * tf[w] * 2.2 / (tf[w] + 1.2 * (.25 + .75 * len(d) / self.avg)) for w in qs if w in tf)
            if s > 0:
                out.append((i, s))
        out.sort(key=lambda t: -t[1])
        return out[:k]


class PhoneRetriever:
    def __init__(self, cl, route, mode):
        self.cl, self.route, self.mode = cl, route, mode
        self.name = "phone %s %s" % (route, mode)
        self.ok = True

    def search(self, q, k=10):
        st, obj = self.cl.call("POST", self.route, {"query": q, "mode": self.mode, "limit": k}, tag=self.route)
        if st != 200:
            self.ok = False
            return None
        hits = obj.get("hits") if isinstance(obj, dict) else obj
        out = []
        for h in hits or []:
            if isinstance(h, dict) and (h.get("memoryId") or h.get("id")):
                out.append((h.get("memoryId") or h.get("id"), float(h.get("score") or h.get("fused") or 0)))
        return out[:k]


def derive_queries(mems, bm):
    """(a) the 3-5 most distinctive content words by tf-idf; (b) paraphrase-lite: noun/number-like words, stopwords dropped, reordered."""
    qs = {}
    for x in mems:
        cw = content(x["text"])
        if len(set(cw)) < 3:
            continue
        tf = collections.Counter(cw)
        ranked = sorted(tf, key=lambda w: (-tf[w] * bm.idf(w), w))
        a = ranked[:5] if len(ranked) >= 5 else ranked[:max(3, len(ranked))]
        b = [w for w in dict.fromkeys(cw) if (w.isdigit() or (w not in VERBISH and not w.endswith("ly") and not w.endswith("ing")))]
        b = sorted(b, key=lambda w: (len(w), w))[::-1][:6]  # deterministic reorder: longest first
        if len(b) < 2:
            b = cw[::-1][:5]
        qs[x["id"]] = {"tfidf": " ".join(a), "paraphrase": " ".join(b)}
    return qs


def stratified_sample(mems, n, seed):
    rnd = random.Random(seed)
    lens = sorted(len(x["text"]) for x in mems)
    t1 = lens[len(lens) // 3] if lens else 0
    t2 = lens[2 * len(lens) // 3] if lens else 0
    def band(x):
        L = len(x["text"]); return "short" if L < t1 else ("medium" if L < t2 else "long")
    buckets = collections.defaultdict(list)
    for x in mems:
        x["band"] = band(x)
        buckets[(day_of(x["ts"]), x["band"])].append(x)
    for b in buckets.values():
        rnd.shuffle(b)
    keys = sorted(buckets)
    out = []
    while len(out) < n and any(buckets[k] for k in keys):
        for k in keys:
            if buckets[k] and len(out) < n:
                out.append(buckets[k].pop())
    return out


def eval_retrieval(ctx, out):
    a, cl = ctx.a, ctx.cl
    R = {"area": "retrieval", "lines": [], "m": {}}
    L = R["lines"]
    L.append("Phone route: none shipped scores an arbitrary query without the Answer model. GET /api/asks/:id re-runs retrieval only for a stored ask "
             "(used below as real-ask diagnostics); POST /api/asks/:id/replay calls the Answer model and is never sent. "
             "Pass --retrieve-route <path> once a read-only retrieval debug route exists (body {query,mode,limit}; reply {hits:[{memoryId,score}]}).")
    ms = [x for x in ctx.mems() if x["text"].strip()]
    if not ms:
        L.append("- **unavailable**: no memory text could be read (GET /api/frames/:id)"); return R
    bm = LocalBM25(ms)
    qs = derive_queries(ms, bm)
    sample = stratified_sample([x for x in ms if x["id"] in qs], a.n, a.seed)
    L.append("- corpus: %d memories; sample: %d (stratified by day x length tercile, seed %d); 2 queries each (tf-idf words, paraphrase-lite)" % (len(ms), len(sample), a.seed))
    retrievers = []
    if a.retrieve_route:
        for mode in a.modes.split(","):
            retrievers.append(PhoneRetriever(cl, a.retrieve_route, mode.strip().upper()))
    retrievers.append(bm)
    neg = [q for q in NEGATIVE_QUERIES if not (set(content(q)) & set(bm.df))]
    for r in retrievers:
        res, fails, top_true = collections.defaultdict(list), [], []
        dead = False
        for x in sample:
            for kind in ("tfidf", "paraphrase"):
                hits = r.search(qs[x["id"]][kind], 10)
                if hits is None:
                    dead = True; break
                ids = [h[0] for h in hits]
                rank = ids.index(x["id"]) + 1 if x["id"] in ids else None
                res[kind].append(rank)
                if rank == 1:
                    top_true.append(hits[0][1])
                if rank is None or rank > 5:
                    fails.append((x, kind))
            if dead:
                break
        if dead:
            L.append("- %s: **unavailable** (%s)" % (r.name, cl.unavailable.get(r.route))); continue
        L.append("- **%s**" % r.name)
        L.append("  | query | n | R@1 | R@3 | R@5 | R@10 | MRR@10 |")
        L.append("  |---|---|---|---|---|---|---|")
        allr = []
        for kind in ("tfidf", "paraphrase"):
            rk = res[kind]; allr += rk
            L.append("  | %s | %d | %s |" % (kind, len(rk), " | ".join(
                ["%.3f" % share(sum(1 for v in rk if v and v <= k), len(rk)) for k in (1, 3, 5, 10)] + ["%.3f" % share(sum(1.0 / v for v in rk if v), len(rk))])))
        r5 = share(sum(1 for v in allr if v and v <= 5), len(allr))
        mrr = share(sum(1.0 / v for v in allr if v), len(allr))
        R["m"].setdefault("modes", {})[r.name] = {"r5": r5, "mrr": mrr, "phone": r is not bm}
        fids = sorted({x["id"] for x, _ in fails})
        L.append("  - failures (miss or rank > 5): %d queries over %d memories; ids: %s" % (len(fails), len(fids), ", ".join(fids[:40]) + (" ..." if len(fids) > 40 else "")))
        for dim in ("band", "day", "type", "state"):
            tot = collections.Counter(str(x.get(dim) if dim != "day" else day_of(x["ts"])) for x in sample)
            bad = collections.Counter(str(x.get(dim) if dim != "day" else day_of(x["ts"])) for x, _ in fails)
            L.append("  - failure rate by %s: %s" % ({"band": "length", "state": "review/frame state"}.get(dim, dim),
                     ", ".join("%s %s" % (k, pct(bad.get(k, 0), 2 * v)) for k, v in sorted(tot.items()))))
        # negative control
        nt = []
        for q in neg:
            h = r.search(q, 10) or []
            nt.append(h[0][1] if h else 0.0)
        thr = quartiles(top_true)["median"] if top_true else None
        fp = sum(1 for s in nt if thr is not None and s >= thr) if nt else 0
        L.append("  - negative control: %d queries never captured (vocabulary-checked); returning any hit %d; top score %s; "
                 "scoring >= median true-hit score (%s): %d (%s)" % (len(neg), sum(1 for s in nt if s > 0), fq(quartiles(nt), "%.3f"),
                 "n/a" if thr is None else "%.3f" % thr, fp, pct(fp, len(neg))))
        R["m"]["modes"][r.name]["neg_fp"] = share(fp, len(neg))
        if a.samples:
            L.append("  - sample queries:")
            for x in sample[:a.samples]:
                L.append("    - %s: `%s` / `%s`" % (x["id"], snip(qs[x["id"]]["tfidf"]), snip(qs[x["id"]]["paraphrase"])))
    # real-ask diagnostics
    R["m"]["asks"] = ask_views(ctx)
    av = R["m"]["asks"]
    if av is None:
        L.append("- real asks (GET /api/asks/:id): **unavailable** (%s)" % (cl.unavailable.get("/api/asks") or cl.unavailable.get("/api/asks/:id")))
    else:
        views = [v for v in av["views"] if v]
        modes = collections.Counter(str(v.get("mode")) for v in views)
        fb = sum(1 for v in views if v.get("fallback"))
        L.append("- real asks re-retrieved: %d views (%d redacted/refused); retrieval modes %s; fallback %d (%s)" % (
            len(views), av["refused"], dict(modes), fb, pct(fb, len(views))))
        for key in ("lexical", "semantic", "fused", "score"):
            vals = [h.get(key) for v in views for h in v.get("hits") or [] if isinstance(h.get(key), (int, float))]
            L.append("  - hit %s score: %s" % (key, fq(quartiles(vals), "%.3f")))
        L.append("  - hits per ask: %s; in prompt per ask: %s" % (
            fq(quartiles([len(v.get("hits") or []) for v in views]), "%.0f"),
            fq(quartiles([sum(1 for h in v.get("hits") or [] if h.get("inPrompt")) for v in views]), "%.0f")))
    return R


def ask_views(ctx):
    if hasattr(ctx, "_asks"):
        return ctx._asks
    cl = ctx.cl
    lst = cl.get("/api/asks", tag="/api/asks")
    if not isinstance(lst, list):
        ctx._asks = None; return None
    rows = [r for r in lst if isinstance(r, dict)][:ctx.a.max_asks]
    views, refused = [], 0
    for r in rows:
        if r.get("redacted"):
            refused += 1; views.append(None); continue
        st, v = cl.call("GET", "/api/asks/%s" % urllib.parse.quote(str(r.get("id")), safe=""), tag="/api/asks/:id")
        if st == 200 and isinstance(v, dict):
            views.append(v)
        else:
            refused += 1; views.append(None)
    ctx._asks = {"rows": rows, "views": views, "refused": refused}
    return ctx._asks

# ---------------------------------------------------------------- 3. canvas

def eval_canvas(ctx, out):
    a, cl = ctx.a, ctx.cl
    R = {"area": "canvas", "lines": [], "m": {}}
    L, m = R["lines"], R["m"]
    L.append("Routes: GET /api/pipelines, /api/pipelines/:id, /api/runs, /api/runs/:id, /api/runs/:id/proposal, /api/publish, /api/publish/history, "
             "/api/live, /api/plans/status, /api/signals, /api/traces, /api/replays, /api/sweeps, /api/devices.")
    pl = cl.get("/api/pipelines", tag="/api/pipelines")
    if not isinstance(pl, list):
        L.append("- pipelines: **unavailable** (%s)" % cl.unavailable.get("/api/pipelines"))
        pl = []
    withp = 0
    for p in pl[:a.max_pipelines]:
        d = cl.get("/api/pipelines/%s" % urllib.parse.quote(str(p.get("id")), safe=""), tag="/api/pipelines/:id")
        p["_problems"] = len(d.get("problems") or []) if isinstance(d, dict) else None
        withp += 1 if p["_problems"] else 0
    m["pipelines"] = len(pl); m["pipeline_problem_share"] = share(withp, len(pl))
    if pl:
        L.append("- pipelines: %d (built-in %d, with a live version %d, draft with validation problems %d)" % (
            len(pl), sum(1 for p in pl if p.get("builtIn")), sum(1 for p in pl if p.get("live")), withp))
    runs = cl.get("/api/runs", tag="/api/runs")
    if not isinstance(runs, list):
        L.append("- runs: **unavailable** (%s)" % cl.unavailable.get("/api/runs")); runs = []
    st = collections.Counter(str(r.get("status")) for r in runs)
    m["runs"] = len(runs)
    m["run_failed_share"] = share(sum(v for k, v in st.items() if k.upper() in ("FAILED", "ERROR", "CANCELLED", "CANCELED")), len(runs))
    if runs:
        L.append("- runs kept: %d; status %s" % (len(runs), dict(st)))
    byp = collections.defaultdict(list)
    for r in runs:
        byp[r.get("pipelineId")].append(r)
    items_tot = items_err = items_rev = 0
    prop_tot = prop_saved = 0
    if byp:
        L.append("  | pipeline | runs | last status | calls/est | failures | node errors | needs-review | wall s | median call ms |")
        L.append("  |---|---|---|---|---|---|---|---|---|")
    for pid, rs in sorted(byp.items(), key=lambda kv: str(kv[0])):
        rs.sort(key=lambda r: -(r.get("startedAt") or 0))
        ne = nr = ni = 0; lat = []
        for r in rs[:a.runs_per_pipeline]:
            d = cl.get("/api/runs/%s" % urllib.parse.quote(str(r.get("runId")), safe=""), tag="/api/runs/:id")
            if not isinstance(d, dict):
                continue
            its = d.get("items") or []
            ni += len(its)
            ne += sum(1 for i in its if i.get("error") or str(i.get("status")).upper() in ("ERROR", "FAILED"))
            nr += sum(1 for i in its if "REVIEW" in str(i.get("status")).upper())
            u = d.get("usage") or {}
            if isinstance(u.get("medianLatencyMs"), (int, float)):
                lat.append(u["medianLatencyMs"])
            pr = cl.get("/api/runs/%s/proposal" % urllib.parse.quote(str(r.get("runId")), safe=""), tag="/api/runs/:id/proposal")
            if isinstance(pr, dict) and pr.get("saveable") is not None:
                mm = pr.get("memories") or []
                nr += sum(1 for x in mm if x.get("review"))
                if pr.get("saveable") or pr.get("saved"):
                    prop_tot += 1; prop_saved += 1 if pr.get("saved") else 0
        items_tot += ni; items_err += ne; items_rev += nr
        last = rs[0]
        wall = [((r.get("finishedAt") or 0) - (r.get("startedAt") or 0)) / 1000.0 for r in rs if r.get("finishedAt") and r.get("startedAt")]
        L.append("  | %s | %d | %s | %s/%s | %s | %d/%d | %d | %s | %s |" % (pid, len(rs), last.get("status"), last.get("calls"), last.get("estCalls"),
                 last.get("failures"), ne, ni, nr, "%.1f" % quartiles(wall)["median"] if wall else "n/a", "%.0f" % quartiles(lat)["median"] if lat else "n/a"))
    m["items"] = items_tot
    m["node_error_share"] = share(items_err, items_tot)
    m["needs_review_share"] = share(items_rev, items_tot)
    m["proposals"] = (prop_tot, prop_saved)
    if items_tot:
        L.append("- node results read (last %d runs per pipeline): %d; errors %s; needs-review %s" % (a.runs_per_pipeline, items_tot, pct(items_err, items_tot), pct(items_rev, items_tot)))
    pub = cl.get("/api/publish", tag="/api/publish")
    hist = cl.get("/api/publish/history?limit=50", tag="/api/publish/history")
    if isinstance(pub, dict):
        stack = pub.get("stack") or []
        L.append("- active publishes: %d (%s)" % (len(stack), ", ".join("%s/%s %s@%s" % (s.get("kind"), s.get("mode"), s.get("pipelineId"), s.get("version")) for s in stack)))
    else:
        L.append("- active publish: **unavailable** (%s)" % cl.unavailable.get("/api/publish"))
    if isinstance(hist, list):
        kinds = collections.Counter(str(h.get("kind")) for h in hist)
        rb = [h for h in hist if h.get("rolledBackAt")]
        flows = [h for h in hist if "flow" in str(h.get("kind")).lower()]
        m["rollback_share"] = share(len(rb), len(hist)); m["publishes"] = len(hist)
        L.append("- publish history: %d rows, kinds %s; rolled back %d (%s), reasons %s" % (
            len(hist), dict(kinds), len(rb), pct(len(rb), len(hist)), dict(collections.Counter(str(h.get("rollbackReason")) for h in rb))))
        L.append("- flow plans (Query/Nudge) published: %d; still active %d; last executions: unavailable (no flow-execution route)" % (
            len(flows), sum(1 for h in flows if not h.get("rolledBackAt"))))
    else:
        L.append("- publish history: **unavailable** (%s)" % cl.unavailable.get("/api/publish/history"))
    live = cl.get("/api/live?limit=100", tag="/api/live")
    if isinstance(live, dict) and live.get("active"):
        fr = live.get("frames") or []
        be = sum(1 for f in fr if f.get("batch") == "ERROR")
        ne = sum(1 for f in fr for n in f.get("nodes") or [] if n.get("error"))
        m["live_batch_error_share"] = share(be, len(fr)); m["live_frames"] = len(fr)
        L.append("- live interpreter (%s %s@%s): interpreted %s, fallbacks %s; recent frames %d, batch ERROR %d (%s), node errors %d" % (
            live.get("mode"), live.get("pipelineId"), live.get("version"), live.get("interpreted"), live.get("fallbacks"), len(fr), be, pct(be, len(fr)), ne))
    elif isinstance(live, dict):
        L.append("- live interpreter: none active")
    else:
        L.append("- live interpreter: **unavailable** (%s)" % cl.unavailable.get("/api/live"))
    ps = cl.get("/api/plans/status", tag="/api/plans/status")
    L.append("- capture plan status: %s" % (("keys " + ", ".join(sorted(ps.keys())[:12])) if isinstance(ps, dict) else "**unavailable** (%s)" % cl.unavailable.get("/api/plans/status")))
    sg = cl.get("/api/signals", tag="/api/signals")
    if isinstance(sg, dict):
        s = sg.get("signals") or []; dv = sg.get("derived") or []
        bad = sum(1 for d in dv if not isinstance(d.get("graph"), (dict, list)))
        L.append("- signals: %d catalogue (%d disabled); derived %d (graph missing/unparsable %d); guard configs: unavailable (no route)" % (
            len(s), sum(1 for x in s if x.get("enabled") is False), len(dv), bad))
    else:
        L.append("- signals / guard configs: **unavailable** (%s)" % cl.unavailable.get("/api/signals"))
    for path, label in (("/api/traces", "traces"), ("/api/replays", "replays"), ("/api/sweeps", "sweeps"), ("/api/devices", "devices")):
        v = cl.get(path, tag=path)
        if isinstance(v, list):
            stc = collections.Counter(str(x.get("status") or x.get("state") or x.get("jobState") or "?") for x in v if isinstance(x, dict))
            L.append("- %s: %d, status %s" % (label, len(v), dict(stc)))
            if label in ("traces", "replays") and v:
                m.setdefault("trace_bad", 0)
                m["trace_bad"] += sum(c for k, c in stc.items() if k.upper() in ("FAILED", "PARTIAL", "ERROR", "CORRUPT"))
                m.setdefault("trace_n", 0); m["trace_n"] += len(v)
        elif isinstance(v, dict):
            L.append("- %s: present (%d keys)" % (label, len(v)))
        else:
            L.append("- %s: **unavailable** (%s)" % (label, cl.unavailable.get(path)))
    return R

# ---------------------------------------------------------------- 4. output

def eval_output(ctx, out):
    a, cl = ctx.a, ctx.cl
    R = {"area": "output", "lines": [], "m": {}}
    L, m = R["lines"], R["m"]
    L.append("Routes: GET /api/asks, GET /api/asks/:id (re-runs retrieval as of the ask; no model call). Nudges and proposal undo: no route.")
    av = ask_views(ctx)
    if av is None:
        L.append("- asks: **unavailable** (%s)" % cl.unavailable.get("/api/asks")); return R
    rows, views = av["rows"], av["views"]
    m["asks"] = len(rows)
    L.append("- asks read: %d (newest, cap --max-asks %d); redacted %d; views refused/failed %d" % (
        len(rows), a.max_asks, sum(1 for r in rows if r.get("redacted")), av["refused"]))
    if not rows:
        return R
    live_rows = [r for r in rows if not r.get("redacted")]
    ql = [len(toks(r.get("question"))) for r in live_rows]
    L.append("- question length words: %s" % fq(quartiles(ql), "%.0f"))
    empty = [r for r in live_rows if not str(r.get("answer") or "").strip()]
    idk = [r for r in live_rows if any(p in str(r.get("answer") or "").lower() for p in IDK)]
    m["live_asks"] = len(live_rows)
    m["empty_share"] = share(len(empty), len(live_rows)); m["idk_share"] = share(len(idk), len(live_rows))
    L.append("- answer empty %d (%s); 'I don't know'-style %d (%s)" % (len(empty), pct(len(empty), len(live_rows)), len(idk), pct(len(idk), len(live_rows))))
    rated = [r.get("rating") for r in live_rows if r.get("rating") is not None]
    L.append("- ratings: %d rated; %s" % (len(rated), dict(collections.Counter(rated))))
    known = {x["id"] for x in ctx.mems()} | {f.get("id") for f in ctx.frames()}
    vs = [v for v in views if v]
    cit_tot = unres = outside = asks_out = 0
    for v in vs:
        hit_ids = {h.get("memoryId") for h in v.get("hits") or []} | {h.get("frameId") for h in v.get("hits") or [] if h.get("frameId")}
        cited = [c for c in v.get("cited") or []]
        cit_tot += len(cited)
        unres += sum(1 for c in cited if c not in known and c not in hit_ids)
        o = sum(1 for c in cited if c not in hit_ids)
        outside += o; asks_out += 1 if o else 0
    m["citations"] = cit_tot
    m["unresolved_share"] = share(unres, cit_tot); m["outside_share"] = share(outside, cit_tot)
    L.append("- citations: %d over %d views; not resolving to a memory/photo read by this tool or a retrieved hit %d (%s); "
             "cited but not among re-retrieved hits %d (%s) in %d asks" % (cit_tot, len(vs), unres, pct(unres, cit_tot), outside, pct(outside, cit_tot), asks_out))
    L.append("  - note: the view re-retrieves as of the ask and is refused when any hit was forgotten, so 'outside' can also mean the index changed since")
    L.append("- ask latency: unavailable (not stored on asks; only replays report latency, and replays are never sent)")
    L.append("- Nudges (count, dedupe, text length, sourceIds, shown-on): **unavailable** (no nudge route on the canvas server)")
    tot, saved = ctx_results(out, "canvas", "proposals") or (None, None)
    if tot is not None:
        L.append("- run proposals (Run-and-Save): %d saveable/saved, %d saved (%s); undone: unavailable (no route)" % (tot, saved, pct(saved, tot)))
    if a.samples:
        L.append("  - sample questions: " + "; ".join("`%s`" % snip(r.get("question")) for r in live_rows[:a.samples]))
    return R


def ctx_results(out, area, key):
    r = out.get(area)
    return r["m"].get(key) if r else None

# ---------------------------------------------------------------- 5. scoring

def clamp(x):
    return max(0, min(100, int(round(x))))


def score(out):
    S, issues = {}, []
    DEN = {"failed_share": "frames", "no_memory_share": "frames", "bad_ts_share": "frames", "dup_share": "memories", "refusal_share": "memories",
           "empty_short_share": "memories", "empty_share": "live_asks", "idk_share": "live_asks", "unresolved_share": "citations",
           "outside_share": "citations", "run_failed_share": "runs", "node_error_share": "items", "needs_review_share": "items",
           "pipeline_problem_share": "pipelines", "rollback_share": "publishes", "live_batch_error_share": "live_frames"}
    def ev(m, k):
        d = DEN.get(k); n = m.get(d) if d else None
        return ("%d of %d %s (%.1f %%)" % (round(m[k] * n), n, d.replace("_", " "), 100 * m[k])) if n else "%.1f %%" % (100 * m[k])
    def iss(sev, area, text, e):
        issues.append((sev, area, text, e))
    c = out.get("capture")
    if c and c["m"].get("frames"):
        m = c["m"]
        S["capture"] = clamp(100 - 40 * m.get("failed_share", 0) - 20 * m.get("no_memory_share", 0) - 20 * m.get("dup_share", 0)
                             - 10 * m.get("refusal_share", 0) - 5 * m.get("empty_short_share", 0) - 5 * m.get("bad_ts_share", 0))
        for k, sev, t in (("failed_share", 3, "frames whose extraction failed"), ("no_memory_share", 2, "frames with no live memory"),
                          ("dup_share", 2, "duplicate / near-duplicate memories"), ("refusal_share", 3, "memories with refusal/boilerplate text"),
                          ("empty_short_share", 1, "empty or very short memories"), ("bad_ts_share", 3, "frames with future or very old timestamps")):
            if m.get(k, 0) > 0:
                iss(sev * m[k], "capture", t, ev(m, k))
    r = out.get("retrieval")
    if r and r["m"].get("modes"):
        md = r["m"]["modes"]
        phone = {k: v for k, v in md.items() if v["phone"]}
        use = phone or md
        r5 = sum(v["r5"] for v in use.values()) / len(use)
        fp = sum(v.get("neg_fp", 0) for v in use.values()) / len(use)
        S["retrieval"] = clamp(80 * r5 + 20 * sum(v["mrr"] for v in use.values()) / len(use) - 20 * fp)
        S["retrieval_basis"] = "phone" if phone else "reference only (no phone retrieval route)"
        if r5 < .8:
            iss(3 * (1 - r5), "retrieval", "known-summary recall@5 below 0.8 (%s)" % S["retrieval_basis"], "R@5 %.3f" % r5)
        if fp > 0:
            iss(2 * fp, "retrieval", "negative-control queries score like true hits", "%.1f %%" % (100 * fp))
        if not phone:
            iss(1.5, "retrieval", "no read-only retrieval debug route: phone recall cannot be measured", "route missing")
    o = out.get("output")
    if o and o["m"].get("asks"):
        m = o["m"]
        S["output"] = clamp(100 - 30 * m.get("empty_share", 0) - 25 * m.get("unresolved_share", 0) - 25 * m.get("outside_share", 0) - 20 * m.get("idk_share", 0))
        for k, sev, t in (("empty_share", 3, "empty answers"), ("unresolved_share", 3, "citations that resolve to nothing"),
                          ("outside_share", 2, "citations outside the retrieved hits"), ("idk_share", 1, "'I don't know'-style answers")):
            if m.get(k, 0) > 0:
                iss(sev * m[k], "output", t, ev(m, k))
    cv = out.get("canvas")
    if cv and (cv["m"].get("pipelines") or cv["m"].get("runs")):
        m = cv["m"]
        tb = share(m.get("trace_bad", 0), m.get("trace_n", 0))
        S["canvas"] = clamp(100 - 25 * m.get("run_failed_share", 0) - 20 * m.get("node_error_share", 0) - 15 * m.get("pipeline_problem_share", 0)
                            - 15 * m.get("needs_review_share", 0) - 10 * m.get("rollback_share", 0) - 10 * m.get("live_batch_error_share", 0) - 5 * tb)
        for k, sev, t in (("run_failed_share", 3, "runs failed/cancelled"), ("node_error_share", 3, "node results with errors"),
                          ("pipeline_problem_share", 1, "pipeline drafts with validation problems"), ("needs_review_share", 1, "node results needing review"),
                          ("rollback_share", 2, "publishes rolled back"), ("live_batch_error_share", 3, "live interpreter batches in ERROR")):
            if m.get(k, 0) > 0:
                iss(sev * m[k], "canvas", t, ev(m, k))
        if tb:
            iss(2 * tb, "canvas", "traces/replays failed or partial", "%d of %d" % (m.get("trace_bad", 0), m.get("trace_n", 0)))
    issues.sort(key=lambda t: -t[0])
    return S, issues


RULES = """Rules (each area starts at 100; shares are 0..1; clamped to 0..100; an area with no data is not scored):
- Capture = 100 - 40*failed - 20*no-memory - 20*duplicate - 10*refusal - 5*empty/short - 5*bad-timestamp
- Retrieval = 80*R@5 + 20*MRR - 20*negative-false-positive (mean over phone modes; the local reference only when no phone route exists)
- Canvas = 100 - 25*runs-failed - 20*node-errors - 15*pipelines-with-problems - 15*needs-review - 10*rolled-back - 10*live-batch-errors - 5*trace/replay-bad
- Output = 100 - 30*empty-answer - 25*unresolved-citation - 25*citation-outside-hits - 20*idk
- Issue priority = severity weight (1..3) x share; listed highest first."""

# ---------------------------------------------------------------- driver

AREAS = {"capture": eval_capture, "retrieval": eval_retrieval, "canvas": eval_canvas, "output": eval_output}
TITLES = {"capture": "1. Capture quality", "retrieval": "2. Retrieval self-test", "canvas": "3. Canvas quality", "output": "4. Output quality"}


def render(out, cl, a, S=None, issues=None):
    md = ["# Proactive Context quality evaluation", "",
          "Generated %s against the paired phone (read-only: %d requests, verbs %s). Snippets: %s." % (
              time.strftime("%Y-%m-%d %H:%M %Z"), len(cl.log), sorted({x[0] for x in cl.log}) or "none",
              ("%d per list, 80 chars" % a.samples) if a.samples else "none (pass --samples N)"), ""]
    for k in ("capture", "retrieval", "canvas", "output"):
        if k in out:
            md += ["## " + TITLES[k], ""] + out[k]["lines"] + [""]
    if S is not None:
        md += ["## 5. Scores", "", RULES, "", "| area | score |", "|---|---|"]
        for k in ("capture", "retrieval", "canvas", "output"):
            md.append("| %s | %s |" % (k + (" (%s)" % S["retrieval_basis"] if k == "retrieval" and k in S else ""), S.get(k, "not scored")))
        md += ["", "### Prioritised issues", ""]
        md += ["%d. [%s] %s: %s (priority %.2f)" % (i + 1, ar, t, ev, sv) for i, (sv, ar, t, ev) in enumerate(issues)] or ["none found"]
        md.append("")
    if cl.unavailable:
        md += ["## Unavailable routes", ""] + ["- %s: %s" % kv for kv in sorted(cl.unavailable.items())] + [""]
    return "\n".join(md)


def main(argv=None):
    p = argparse.ArgumentParser(prog="quality-eval", description="Read-only quality evaluation via the canvas LAN server")
    p.add_argument("cmd", choices=["capture", "retrieval", "canvas", "output", "all"])
    p.add_argument("--out", help="markdown report path (default: stdout)")
    p.add_argument("--samples", type=int, default=0, help="include up to N text snippets (80 chars) per list; default none")
    p.add_argument("-n", "--n", type=int, default=100, help="retrieval sample size")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--rate", type=float, default=5.0, help="requests per second (max 5)")
    p.add_argument("--max-frames", type=int, default=3000)
    p.add_argument("--max-detail", type=int, default=400, help="frames whose memories are read (GET /api/frames/:id)")
    p.add_argument("--max-days", type=int, default=14, help="days of Moments read")
    p.add_argument("--max-asks", type=int, default=50)
    p.add_argument("--max-pipelines", type=int, default=30)
    p.add_argument("--runs-per-pipeline", type=int, default=3)
    p.add_argument("--jaccard", type=float, default=0.8)
    p.add_argument("--retrieve-route", help="a read-only retrieval debug POST route, if the phone build has one")
    p.add_argument("--modes", default="LEXICAL,SEMANTIC,HYBRID")
    a = p.parse_args(argv)
    if a.retrieve_route:
        if not re.match(r"^/api/[a-z0-9/_-]*retriev[a-z0-9/_-]*$", a.retrieve_route):
            raise SystemExit("--retrieve-route must be an /api path naming a retrieval route")
        READ_ONLY_POSTS.add(a.retrieve_route)
    cl = Client(rate=a.rate)
    ctx = Ctx(cl, a)
    out = {}
    order = ["capture", "canvas", "retrieval", "output"] if a.cmd == "all" else [a.cmd]
    for k in order:
        out[k] = AREAS[k](ctx, out)
    S, issues = score(out) if a.cmd == "all" else (None, None)
    md = render(out, cl, a, S, issues)
    bad = [x for x in cl.log if x[0] not in ALLOWED_METHODS or (x[0] == "POST" and x[1] not in READ_ONLY_POSTS)]
    assert not bad, "a non-read-only request was sent"
    if a.out:
        with open(a.out, "w") as f:
            f.write(md)
        print("report written: %s (%d requests, %d unavailable routes)" % (a.out, len(cl.log), len(cl.unavailable)))
        if S:
            print("scores: " + ", ".join("%s=%s" % (k, S[k]) for k in ("capture", "retrieval", "canvas", "output") if k in S))
    else:
        sys.stdout.write(md + "\n")


if __name__ == "__main__":
    main()
