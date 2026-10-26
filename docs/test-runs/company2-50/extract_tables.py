"""Prints the per-query rows used in the selected-results tables (2, 3, 5, 6, 7) from raw/*.json."""
import json, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")


def load(n):
    with open(os.path.join(RAW, n + ".json"), encoding="utf8") as f:
        return json.load(f)


s = load("summary")
fid = {f["id"]: f for f in s["fidelity"]}
print("== Table 2 ==")
for i in ["A1", "A4", "A11", "A13", "A17", "A20", "A23", "A24"]:
    f = fid[i]
    print(i, f["kind"], f["match"], f["raw_cost"], f["qs_cost"], json.dumps(f["raw"]))
print("rows total", sum(f["tables"] for f in s["fidelity"]))

print("== Table 3 ==")
for i in ["A3", "A24", "A13", "A11", "A7", "A1", "A26"]:
    f = fid[i]
    v = f["verdict"] or {}
    print(i, v.get("severity"), "|", v.get("headline"), "|", json.dumps(f["raw"]))

print("== Table 5 ==")
pq = {p["id"]: p for p in s["levels_per_query"]}
for i in ["A13", "A11", "A24"]:
    for lv in ("LOW", "MEDIUM", "HIGH"):
        print(i, lv, json.dumps(pq[i][lv]))

print("== Table 6 ==")
for e in load("index"):
    if e.get("skipped"):
        continue
    b, a = e["before"], e["after"]
    print(e["id"], e["sql"], "|", e["indexDdl"][0], "|", b["accessType"], "->", a["accessType"], "|",
          b["estimatedRows"], "->", a["estimatedRows"], "|", b["rawCost"], "->", a["rawCost"], "|",
          b["verdict"], "->", a["verdict"], "|", b["advisorCount"], "->", a["advisorCount"], "|",
          round(b["timing"]["median_ms"], 3), "->", round(a["timing"]["median_ms"], 3),
          f"({100 * (a['timing']['median_ms'] / b['timing']['median_ms'] - 1):+.1f}%)", "|",
          b["timing"]["min_ms"], b["timing"]["max_ms"], a["timing"]["min_ms"], a["timing"]["max_ms"], "| restored",
          e["restored"], e["createError"], e["dropError"])
    print("   before EA:", b["explainAnalyze"].splitlines()[0][:200] if b["explainAnalyze"] else None)
    print("   after  EA:", a["explainAnalyze"].splitlines()[0][:200] if a["explainAnalyze"] else None)

print("== Table 7 ==")
for c in load("compare"):
    r = c["response"] if isinstance(c["response"], dict) else {}
    keys = {k: r.get(k) for k in r if k not in ("originalQuery", "rewrittenQuery", "original", "rewritten")}
    print(c["id"], c["status"], json.dumps(keys)[:400])
