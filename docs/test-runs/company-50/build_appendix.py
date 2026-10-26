"""Builds the raw-data appendices for REPORT.md from results_final.json."""
import json, re, sys

src, out = sys.argv[1], sys.argv[2]
R = json.load(open(src, encoding="utf8"))
L = []
w = L.append


def cell(s):
    return str(s if s is not None else "—").replace("|", "\\|").replace("\n", " ")


def code(s):
    return "```sql\n" + (s or "").strip() + "\n```"


w("\n---\n\n# Appendices: raw data\n")
w("Everything below is generated directly from `results_final.json` (same run). Nothing is retyped by hand.\n")

# A. upload
up = R["upload"]
w("## Appendix A. Upload checks (raw `information_schema` output)\n")
w(f"HTTP {up['status']} in {up['ms']} ms. Warnings: {up['response'].get('warnings')}.\n")
for k in ("row_counts", "fk_constraints", "indexes", "orphans", "file_rows", "file_departments"):
    v = up["db_checks"][k]
    w(f"**{k}**\n")
    w("```\n" + (json.dumps(v, indent=1) if isinstance(v, dict) else v.strip()) + "\n```\n")
cc = R["clear_cache"]
w(f"Cache clear for label `{cc['label']}`: deleted {len(cc['deleted_rows'])} rows, remaining {cc['remaining']}.\n")

# B. per question
g = R["generate"]
w("## Appendix B. All 50 questions: full request results\n")
for r in g["generate"]:
    w(f"### Q{r['id']}. {r['question']}\n")
    w(f"- Category: {r['category']}" + (f"; paraphrase group {r['paraphraseGroup']}" if r.get("paraphraseGroup") else ""))
    w(f"- Path: **{r.get('handledBy')}**, confidence {r.get('confidence')}, {r['ms']} ms (HTTP {r['status']})")
    if r.get("declineReason"):
        w(f"- Rule-engine decline reason: \"{r['declineReason']}\"")
    if r.get("interpretations"):
        w("- Read as: " + "; ".join(f"\"{i['phrase']}\" → `{i['item']}` ({i['kind']})" for i in r["interpretations"]))
    if r.get("schemaWarning"):
        w(f"- Warning: \"{r['schemaWarning']}\"")
    ex = r.get("execution", {})
    if ex.get("executed"):
        w(f"- Executed in MySQL: {'ran, ' + str(ex.get('rows')) + ' rows, columns ' + ', '.join(ex.get('cols') or []) if ex.get('runs') else 'FAILED: ' + str(ex.get('error'))}")
    else:
        w(f"- Executed in MySQL: no ({ex.get('why')})")
    w(f"- Ground truth: {r.get('truthResult')}" + (f" (truth rows {r['truthRows']})" if r.get("truthRows") is not None else ""))
    w("\nGenerated:\n")
    w(code(r.get("generatedQuery")))
    if r.get("truthSql"):
        w("\nGround truth:\n")
        w(code(r["truthSql"]))
    w("")

w("### Second ask (cache check)\n")
w("| Q | First path | Second path | Conf. | ms | Same SQL | Warning |")
w("|---|---|---|---|---|---|---|")
for a in g["again"]:
    w(f"| {a['id']} | {a['first']} | {a['handledBy']} | {a['confidence']} | {a['ms']} | {'yes' if a['identical'] else 'NO'} | {cell(a.get('schemaWarning'))} |")
w(f"\nRow counts before generation: {g['row_counts_before']}. After the second asks: {g['row_counts_after']}.\n")
w(f"`query_history` rows for the label after the run: {len(g['history_after'])} (id, source, confidence, question):\n")
w("```\n" + "\n".join(g["history_after"]) + "\n```\n")

# C. tokens
t = R["tokens"]
llm_q = {r["question"] for r in g["generate"] if r.get("handledBy") == "OLLAMA"}
w("## Appendix C. Tokens per question\n")
w("LLM-only baseline, one direct Ollama call per question with the backend's prompt. *Pipeline* marks the 17 questions the backend itself sent to the model in §2.\n")
w("| Q | Question | Pipeline call? | Input tokens | Output tokens | Ollama total ms | Model output |")
w("|---|---|---|---|---|---|---|")
for b in t["baseline"]:
    w(f"| {b['id']} | {cell(b['question'])} | {'yes' if b['question'] in llm_q else 'no'} | {b['prompt_eval_count']} | {b['eval_count']} | "
      f"{round((b['total_duration_ns'] or 0) / 1e6, 1)} | `{cell((b.get('response') or '').strip())[:160]}` |")
w("\nCalls the backend made during the run (temporary log, in order):\n")
w("| # | Question in prompt | Input tokens | Output tokens | Ollama total ms |")
w("|---|---|---|---|---|")
for i, c in enumerate(t["pipeline_logged_calls"], 1):
    m = re.search(r"Natural language input: (.*)\n", c["prompt"])
    w(f"| {i} | {cell(m.group(1) if m else '?')} | {c['prompt_eval_count']} | {c['eval_count']} | {round(int(c['total_duration_ns']) / 1e6, 1)} |")
ok = sum(x["identical_to_backend_prompt"] for x in t["reproduction_check"])
w(f"\nPrompt reproduction check: {ok} of {len(t['reproduction_check'])} logged prompts identical to the template.\n")
w("Prompt template (`{Q}` = the question):\n")
w("```text\n" + t["template"] + "\n```\n")

# D. analysis
w("## Appendix D. Analysis: raw EXPLAIN, verdict, suggestions at every level, advisor\n")
for e in R["analyze"]:
    w(f"### {e['id']} ({e['source']}; {e['kind']})\n")
    w(code(e["sql"]))
    w("\nRaw `EXPLAIN FORMAT=TRADITIONAL`:\n")
    w("| table | type | possible_keys | key | rows | filtered | Extra |")
    w("|---|---|---|---|---|---|---|")
    for row in e["raw_explain"]:
        w(f"| {cell(row.get('table'))} | {cell(row.get('type'))} | {cell(row.get('possible_keys'))} | {cell(row.get('key'))} | "
          f"{cell(row.get('rows'))} | {cell(row.get('filtered'))} | {cell(row.get('Extra'))} |")
    an = e["levels"]["MEDIUM"]["response"]["analysis"]
    v = an.get("verdict") or {}
    w(f"\n`query_cost` MySQL {e['raw_query_cost']} · QuerySense {an.get('queryCost')}. "
      f"Verdict **{v.get('severity')}**: \"{v.get('headline')}\"" + (f" — {v.get('detail')}" if v.get("detail") else "") + "\n")
    w("| Level | ms | Source | Severity | Message |")
    w("|---|---|---|---|---|")
    for lv in ("LOW", "MEDIUM", "HIGH"):
        resp = e["levels"][lv]
        sugg = resp["response"].get("suggestions") or []
        if not sugg:
            w(f"| {lv} | {resp['ms']} | — | — | (no suggestions) |")
        for s in sugg:
            w(f"| {lv} | {resp['ms']} | {s['source']} | {s['severity']} | {cell(s['message'])} |")
    adv = e["advisor"]["response"]
    w("\nSchema advisor:\n")
    if isinstance(adv, list) and adv:
        for a in adv:
            w(f"- **{a.get('type')}** on {a.get('targetTable')}{'.' + a['targetColumn'] if a.get('targetColumn') else ''}: "
              f"`{cell(a.get('suggestedAction'))}` — {cell(a.get('reason'))}")
    else:
        w("- (no recommendations)")
    w("")

# E. index
w("## Appendix E. Index before/after: full measurements\n")
for e in R["index"]:
    if e.get("skipped"):
        continue
    w(f"### {e['id']}: `{e['indexDdl'][0]}`\n")
    w(code(e["sql"]))
    for ph in ("before", "after"):
        s = e[ph]
        tm = s["timing"]
        w(f"\n**{ph.capitalize()}**: access {s['accessType']}, key {s['keyUsed']}, est. rows {s['estimatedRows']}, "
          f"QuerySense cost {s['queryCost']}, MySQL `query_cost` {s['rawCost']}, verdict {s['verdict']} "
          f"(\"{s['headline']}\"), advisor recommendations {s['advisorCount']}.\n")
        w(f"Timed runs (ms, 10 after 3 warm-ups): {', '.join(f'{x:.3f}' for x in tm['times_ms'])} → median {tm['median_ms']:.3f}, "
          f"min {tm['min_ms']:.3f}, max {tm['max_ms']:.3f}.\n")
        w("`EXPLAIN ANALYZE`:\n")
        w("```text\n" + s["explainAnalyze"].strip() + "\n```")
    w(f"\nIndex dropped afterwards; `SHOW INDEX` identical to before: **{e['restored']}**.\n")
skipped = [e["id"] for e in R["index"] if e.get("skipped")]
w(f"No runnable `CREATE INDEX` recommended for: {', '.join(skipped)}.\n")

# F. compare
w("## Appendix F. Rewrite (`/api/query/compare`) per query\n")
w("| ID | Rewritten | Rule(s) | Est. rows before → after | Cost before → after | Summary |")
w("|---|---|---|---|---|---|")
for e in R["compare"]:
    r = e["response"]
    rules = ", ".join(c.get("ruleId", "?") for c in (r.get("changes") or [])) or "—"
    oa, na = r.get("originalAnalysis") or {}, r.get("optimizedAnalysis") or {}
    w(f"| {e['id']} | {r.get('rewritten')} | {rules} | {oa.get('estimatedRows')} → {na.get('estimatedRows') if r.get('rewritten') else '—'} | "
      f"{oa.get('queryCost')} → {na.get('queryCost') if r.get('rewritten') else '—'} | {cell(r.get('summary'))} |")
w("")

# G. mongo
w("## Appendix G. MongoDB questions (built-in demo schema)\n")
for e in R["mongo"]:
    r = e["generate"]["response"]
    w(f"### {e['id']}. {e['question']}\n")
    w(f"- Path {r.get('handledBy')}, confidence {r.get('confidence')}, {e['generate']['ms']} ms")
    if r.get("schemaWarning"):
        w(f"- Warning: \"{r['schemaWarning']}\"")
    if (r.get("ruleTrace") or {}).get("declineReason"):
        w(f"- Decline reason: \"{r['ruleTrace']['declineReason']}\"")
    w("\n```js\n" + (r.get("generatedQuery") or "") + "\n```")
    a = (e.get("analyze") or {}).get("response")
    if a:
        an = a.get("analysis") or {}
        v = an.get("verdict") or {}
        w(f"\nAnalyze ({e['analyze']['ms']} ms): plan {an.get('accessType')}, documents {an.get('estimatedRows')}, "
          f"verdict {v.get('severity')} \"{v.get('headline')}\".")
        for s in a.get("suggestions") or []:
            w(f"- {s['source']} {s['severity']}: {s['message']}")
    w("")

# H. environment
w("## Appendix H. Environment (raw)\n")
w("```json\n" + json.dumps({k: v for k, v in R["env"].items() if k != "ollama_models"}, indent=1) + "\n```\n")

open(out, "w", encoding="utf8").write("\n".join(L) + "\n")
print(len(L), "lines")
