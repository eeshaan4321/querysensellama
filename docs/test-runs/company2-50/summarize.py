"""Turns raw/*.json into the numbers and markdown tables used in REPORT.md. Prints to stdout and writes raw/summary.json."""
import json, os, re, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")


def load(n):
    with open(os.path.join(RAW, n + ".json"), encoding="utf8") as f:
        return json.load(f)


def med(xs):
    return round(statistics.median(xs), 1) if xs else None


summary = {}

# ---- section 2: routing ----
gen = load("generate")
rows = gen["generate"]
paths = {}
for r in rows:
    p = r["handledBy"] if r["handledBy"] != "OLLAMA" or (r.get("confidence") or 0) > 0.2 else "OLLAMA_FLAGGED"
    paths.setdefault(p, []).append(r)
route = {}
for p, rs in paths.items():
    checked = [r for r in rs if r.get("truthSql") and r.get("execution", {}).get("runs")]
    route[p] = {"n": len(rs), "ids": [r["id"] for r in rs],
                "runnable": sum(1 for r in rs if r.get("execution", {}).get("runs")),
                "executed": sum(1 for r in rs if r.get("execution", {}).get("executed")),
                "truth_checked": len(checked),
                "truth_identical": sum(1 for r in checked if r["truthResult"] == "identical"),
                "truth_fewer_cols": sum(1 for r in checked if r["truthResult"] == "same rows with fewer columns"),
                "truth_other": [(r["id"], r["truthResult"]) for r in checked
                                if r["truthResult"] not in ("identical", "same rows with fewer columns")],
                "median_ms": med([r["ms"] for r in rs]), "max_ms": max(r["ms"] for r in rs)}
cache = [a for a in gen["again"] if a["handledBy"] == "CACHE"]
route["CACHE"] = {"n": len(cache), "identical": sum(a["identical"] for a in cache),
                  "median_ms": med([a["ms"] for a in cache]), "max_ms": max(a["ms"] for a in cache)}
summary["routing"] = route
print(json.dumps(route, indent=1))

# ---- section 3: tokens ----
tok = load("tokens")
base = tok["baseline"]
llm_ids = [r["id"] for r in rows if r["handledBy"] == "OLLAMA"]
calls = tok["pipeline_logged_calls"]
first_calls = calls[:len(llm_ids)]
first_qs = [re.search(r"Natural language input: (.*)\n", c["prompt"]).group(1) for c in first_calls]
assert first_qs == [r["question"] for r in rows if r["handledBy"] == "OLLAMA"], "logged order mismatch"


def tot(items, pe="prompt_eval_count", ev="eval_count", dur="total_duration_ns"):
    i = sum(int(x[pe]) for x in items)
    o = sum(int(x[ev]) for x in items)
    d = sum(int(x[dur]) for x in items) / 1e6
    return {"calls": len(items), "input": i, "output": o, "total": i + o, "llm_ms": round(d, 1)}


summary["tokens"] = {
    "llm_only": tot(base),
    "pipeline_first_pass": tot(first_calls),
    "pipeline_with_unsafe_repeats": tot(calls[:len(llm_ids)] + calls[len(llm_ids):]),
    "baseline_same_questions_as_pipeline": tot([b for b in base if b["id"] in llm_ids]),
}
t = summary["tokens"]
for k in ("input", "output", "total", "calls", "llm_ms"):
    t.setdefault("reduction_pct", {})[k] = round(100 * (1 - t["pipeline_first_pass"][k] / t["llm_only"][k]), 1)
print(json.dumps(t, indent=1))

# ---- section 4: fidelity ----
ana = load("analyze")
fid = []
for e in ana:
    a = e["levels"]["MEDIUM"]["response"]["analysis"]
    raw = e["raw_explain"]
    qt = a.get("tables") or []
    mism = []
    if len(raw) != len(qt):
        mism.append(f"table count raw {len(raw)} vs QS {len(qt)}")
    for i, (rr, q) in enumerate(zip(raw, qt)):
        def nz(v):
            return None if v in (None, "NULL", "") else str(v)
        pairs = [("table", rr.get("table"), q.get("tableName")), ("type", rr.get("type"), q.get("accessType")),
                 ("key", rr.get("key"), q.get("keyUsed")), ("rows", rr.get("rows"), q.get("estimatedRows")),
                 ("filtered", rr.get("filtered"), q.get("filtered")), ("Extra", rr.get("Extra"), q.get("extra"))]
        for name, x, y in pairs:
            x, y = nz(x), nz(y)
            if name == "filtered" and x and y:
                same = abs(float(x) - float(y)) < 0.01
            elif name == "rows" and x and y:
                same = int(float(x)) == int(float(y))
            else:
                same = x == y
            if not same:
                mism.append(f"row {i} {name}: MySQL={x} QS={y}")
    cost_ok = (e["raw_query_cost"] is None and a.get("queryCost") is None) or (
        e["raw_query_cost"] is not None and a.get("queryCost") is not None
        and abs(e["raw_query_cost"] - a["queryCost"]) < 0.01)
    fid.append({"id": e["id"], "sql": e["sql"], "kind": e["kind"], "tables": len(raw), "match": not mism,
                "mismatches": mism, "raw_cost": e["raw_query_cost"], "qs_cost": a.get("queryCost"),
                "cost_match": cost_ok,
                "raw": [{k: rr.get(k) for k in ("table", "type", "key", "rows", "filtered", "Extra")} for rr in raw],
                "verdict": a.get("verdict")})
summary["fidelity"] = fid
print("fidelity", sum(f["match"] for f in fid), "/", len(fid), "cost", sum(f["cost_match"] for f in fid))
for f in fid:
    if not f["match"] or not f["cost_match"]:
        print(" ", f["id"], f["mismatches"], f["raw_cost"], f["qs_cost"])

# ---- section 5: resource levels ----
levels = {}
per_query = []
for e in ana:
    pq = {"id": e["id"], "sql": e["sql"]}
    for lv in ("LOW", "MEDIUM", "HIGH"):
        resp = e["levels"][lv]["response"]
        sugg = resp.get("suggestions") or []
        c = levels.setdefault(lv, {"total": 0, "CRITICAL": 0, "WARNING": 0, "INFO": 0, "ms": []})
        c["total"] += len(sugg)
        for s in sugg:
            c[s["severity"]] += 1
        c["ms"].append(e["levels"][lv]["ms"])
        pq[lv] = [{"source": s["source"], "severity": s["severity"], "message": s["message"]} for s in sugg]
    per_query.append(pq)
for lv, c in levels.items():
    c["median_ms"], c["min_ms"], c["max_ms"] = med(c["ms"]), min(c["ms"]), max(c["ms"])
    del c["ms"]
summary["levels"] = levels
summary["levels_per_query"] = per_query
print(json.dumps(levels, indent=1))

with open(os.path.join(RAW, "summary.json"), "w", encoding="utf8") as f:
    json.dump(summary, f, indent=2, default=str)
