"""LLM-only baseline with the prompt the backend builds for each question (schema block narrowed to the tables the question names).

Usage: python tokens_per_question.py   (after `run_company2.py tokens raw/backend.log`; rewrites raw/tokens.json)
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_company2 import QUESTIONS, load, ollama_call, save

tok = load("tokens")
calls = tok["pipeline_logged_calls"]
full = max(calls, key=lambda c: len(c["prompt"]))["prompt"]

lines = full.split("\n")
head_end = next(i for i, l in enumerate(lines) if "Real schema" in l)
table_lines = [l for l in lines if re.match(r"^\s+- \w+\(", l)]
fk_lines = [l for l in lines if "- Relationship:" in l]
last_schema = max(i for i, l in enumerate(lines) if l in table_lines or l in fk_lines)
head, tail = lines[:head_end + 1], lines[last_schema + 1:]


def singular(name):
    if name.endswith("ies"):
        return name[:-3] + "y"
    return name[:-1] if name.endswith("s") else name


def build(question):
    text = question.lower()
    names = [re.match(r"^\s+- (\w+)\(", l).group(1) for l in table_lines]
    hit = [n for n in names if re.search(r"\b" + re.escape(n) + r"\b", text)
           or re.search(r"\b" + re.escape(singular(n)) + r"\b", text)]
    chosen = hit or names
    t = [l for l, n in zip(table_lines, names) if n in chosen]
    f = []
    for l in fk_lines:
        m = re.search(r"Relationship: (\w+)\.\w+ references (\w+)\.", l)
        if m.group(1) in chosen and m.group(2) in chosen:
            f.append(l)
    body = "\n".join(head + t + f + tail)
    return re.sub(r"Natural language input: .*\n", lambda _: "Natural language input: " + question + "\n", body)


check = []
for c in calls:
    q = re.search(r"Natural language input: (.*)\n", c["prompt"]).group(1)
    check.append({"question": q, "identical_to_backend_prompt": build(q) == c["prompt"]})
ok = sum(r["identical_to_backend_prompt"] for r in check)
print("reproduced", ok, "of", len(check))
if ok != len(check):
    sys.exit("prompt reconstruction does not match the backend; baseline not run")

baseline = []
for qid, cat, q, truth, group in QUESTIONS:
    res = ollama_call(build(q))
    res.update({"id": qid, "question": q, "prompt_chars": len(build(q))})
    baseline.append(res)
    print(qid, res["prompt_eval_count"], res["eval_count"], round((res["total_duration_ns"] or 0) / 1e6))
tok.update({"template": "per-question; see tokens_per_question.py", "reproduction_check": check, "baseline": baseline})
save("tokens", tok)
