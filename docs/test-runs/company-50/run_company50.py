"""QuerySense end-to-end test on the Elmasri-Navathe COMPANY schema (50 questions).

Usage: python run_company50.py <phase> [args]
Phases write raw JSON into ./raw/<phase>.json; `report-data` merges them into results_final.json.
"""
import base64, json, os, re, statistics, subprocess, sys, time, urllib.error, urllib.request, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
os.makedirs(RAW, exist_ok=True)
BASE = "http://localhost:8080"
OLLAMA = "http://localhost:11434/api/generate"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe"
DB = "querysense_uploads"
SCHEMA_FILE = os.path.join(HERE, "Employee_Database_Script.sql")
LABEL = "Employee_Database_Script.sql"


def save(name, data):
    with open(os.path.join(RAW, name + ".json"), "w", encoding="utf8") as f:
        json.dump(data, f, indent=2, default=str)


def load(name):
    with open(os.path.join(RAW, name + ".json"), encoding="utf8") as f:
        return json.load(f)


def http(url, body=None, file=None, timeout=180):
    t0 = time.perf_counter()
    if file:
        boundary = uuid.uuid4().hex
        data = open(file, "rb").read()
        payload = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{LABEL}\"\r\n"
                   f"Content-Type: application/sql\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(url, data=payload,
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    else:
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    ms = (time.perf_counter() - t0) * 1000
    try:
        parsed = json.loads(text)
    except ValueError:
        parsed = text
    return status, parsed, round(ms, 1)


def post(path, body=None, file=None):
    return http(BASE + path, body, file)


def mysql(sql, db=DB, headers=False):
    args = [MYSQL, "-uroot", "-ppassword", "-h127.0.0.1", "-B", db, "-e", sql]
    if not headers:
        args.insert(5, "-N")
    p = subprocess.run(args, capture_output=True, text=True, encoding="utf8")
    err = "\n".join(l for l in p.stderr.splitlines() if "Using a password" not in l).strip()
    return p.stdout, err


def table_result(sql):
    out, err = mysql(sql.rstrip().rstrip(";"), headers=True)
    if err:
        return None, None, err
    lines = out.splitlines()
    if not lines:
        return [], [], None
    cols = lines[0].split("\t")
    rows = [tuple(l.split("\t")) for l in lines[1:]]
    return cols, rows, None


def norm_col(c):
    return c.split(".")[-1].strip("`").lower()


def norm_val(v):
    try:
        f = float(v)
        return f"{f:.4f}"
    except ValueError:
        return v


def compare(gen_cols, gen_rows, truth_cols, truth_rows):
    """identical / same rows, fewer columns / different, comparing as multisets."""
    g = sorted(tuple(sorted(norm_val(v) for v in r)) for r in gen_rows)
    t = sorted(tuple(sorted(norm_val(v) for v in r)) for r in truth_rows)
    if g == t:
        return "identical"
    gn = [norm_col(c) for c in gen_cols]
    tn = [norm_col(c) for c in truth_cols]
    if len(gn) < len(tn) and all(c in tn for c in gn):
        idx = [tn.index(c) for c in gn]
        proj = sorted(tuple(sorted(norm_val(r[i]) for i in idx)) for r in truth_rows)
        if proj == sorted(tuple(sorted(norm_val(v) for v in r)) for r in gen_rows):
            return "same rows with fewer columns"
    if set(g) == set(t):
        return "same set of rows, different multiplicity"
    return "different"


# (id, category, question, ground-truth SQL or None, paraphrase group or None)
QUESTIONS = [
    (1, "simple", "Show all employees", "SELECT * FROM EMPLOYEE", "P4"),
    (2, "simple", "Show employees with salary above 40000", "SELECT * FROM EMPLOYEE WHERE Salary > 40000", "P1"),
    (3, "simple", "Which employees earn more than 40000", "SELECT * FROM EMPLOYEE WHERE Salary > 40000", "P1"),
    (4, "simple", "Show employees with salary between 30000 and 50000",
     "SELECT * FROM EMPLOYEE WHERE Salary BETWEEN 30000 AND 50000", None),
    (5, "simple", "Show projects in Houston", "SELECT * FROM PROJECT WHERE Plocation = 'Houston'", "P8"),
    (6, "simple", "Which projects are located in Houston", "SELECT * FROM PROJECT WHERE Plocation = 'Houston'", "P8"),
    (7, "simple", "Show employees with Dno 5", "SELECT * FROM EMPLOYEE WHERE Dno = 5", None),
    (8, "simple", "Show dependents whose relationship is spouse",
     "SELECT * FROM DEPENDENT WHERE Relationship = 'Spouse'", None),
    (9, "aggregate", "How many employees are there", "SELECT COUNT(*) FROM EMPLOYEE", "P2"),
    (10, "aggregate", "Count the employees", "SELECT COUNT(*) FROM EMPLOYEE", "P2"),
    (11, "aggregate", "Average salary of employees", "SELECT AVG(Salary) FROM EMPLOYEE", "P3"),
    (12, "aggregate", "What is the mean salary of employees", "SELECT AVG(Salary) FROM EMPLOYEE", "P3"),
    (13, "aggregate", "Maximum salary of employees", "SELECT MAX(Salary) FROM EMPLOYEE", None),
    (14, "aggregate", "Minimum salary of employees", "SELECT MIN(Salary) FROM EMPLOYEE", None),
    (15, "aggregate", "Sum of hours of works_on", "SELECT SUM(Hours) FROM WORKS_ON", None),
    (16, "group-by", "How many employees per Dno", "SELECT Dno, COUNT(*) FROM EMPLOYEE GROUP BY Dno", None),
    (17, "group-by", "How many projects per Dnum", "SELECT Dnum, COUNT(*) FROM PROJECT GROUP BY Dnum", None),
    (18, "group-by", "Average salary per Dno", "SELECT Dno, AVG(Salary) FROM EMPLOYEE GROUP BY Dno", None),
    (19, "group-by", "Count of dependents per relationship",
     "SELECT Relationship, COUNT(*) FROM DEPENDENT GROUP BY Relationship", None),
    (20, "group-by", "Total hours per Pno in works_on", "SELECT Pno, SUM(Hours) FROM WORKS_ON GROUP BY Pno", None),
    (21, "sort/top-N", "Top 5 employees by salary", "SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5", "P7"),
    (22, "sort/top-N", "Show the 5 highest paid employees", "SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5", "P7"),
    (23, "sort/top-N", "Show departments sorted by Dname", "SELECT * FROM DEPARTMENT", None),
    (24, "sort/top-N", "Show projects sorted by Pname descending", "SELECT * FROM PROJECT", None),
    (25, "sort/top-N", "Show the oldest employee", "SELECT * FROM EMPLOYEE ORDER BY Bdate ASC LIMIT 1", None),
    (26, "sort/top-N", "First 10 employees sorted by Lname", "SELECT * FROM EMPLOYEE ORDER BY Lname ASC LIMIT 10", None),
    (27, "join", "Show dependents with their employees",
     "SELECT * FROM DEPENDENT JOIN EMPLOYEE ON DEPENDENT.Essn = EMPLOYEE.Ssn", None),
    (28, "join", "Show projects with their department",
     "SELECT * FROM PROJECT JOIN DEPARTMENT ON PROJECT.Dnum = DEPARTMENT.Dnumber", None),
    (29, "join", "Show employees with their department",
     "SELECT * FROM EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber", "P5"),
    (30, "join", "List each employee and the department they work in",
     "SELECT * FROM EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber", "P5"),
    (31, "join", "Show departments and their locations",
     "SELECT * FROM DEPARTMENT JOIN DEPT_LOCATIONS ON DEPARTMENT.Dnumber = DEPT_LOCATIONS.Dnumber", None),
    (32, "join", "Names of employees who work on ProductX",
     "SELECT e.Fname, e.Lname FROM EMPLOYEE e JOIN WORKS_ON w ON e.Ssn = w.Essn JOIN PROJECT p ON w.Pno = p.Pnumber "
     "WHERE p.Pname = 'ProductX'", None),
    (33, "join", "Show employees and the projects they work on",
     "SELECT * FROM EMPLOYEE e JOIN WORKS_ON w ON e.Ssn = w.Essn JOIN PROJECT p ON w.Pno = p.Pnumber", None),
    (34, "join", "Show dependents of employees who work on ProductX",
     "SELECT DISTINCT d.* FROM DEPENDENT d JOIN EMPLOYEE e ON d.Essn = e.Ssn JOIN WORKS_ON w ON e.Ssn = w.Essn "
     "JOIN PROJECT p ON w.Pno = p.Pnumber WHERE p.Pname = 'ProductX'", None),
    (35, "self-join", "Show employees and the name of their supervisor",
     "SELECT e.Fname, e.Lname, s.Fname, s.Lname FROM EMPLOYEE e JOIN EMPLOYEE s ON e.Super_ssn = s.Ssn", None),
    (36, "self-join", "Show employees supervised by Franklin Wong",
     "SELECT * FROM EMPLOYEE WHERE Super_ssn = '333445555'", None),
    (37, "self-join", "How many employees does each supervisor have",
     "SELECT Super_ssn, COUNT(*) FROM EMPLOYEE WHERE Super_ssn IS NOT NULL GROUP BY Super_ssn", None),
    (38, "anti-join", "Employees who have no dependents",
     "SELECT * FROM EMPLOYEE e WHERE NOT EXISTS (SELECT 1 FROM DEPENDENT d WHERE d.Essn = e.Ssn)", "P6"),
    (39, "anti-join", "Which employees do not have any dependents",
     "SELECT * FROM EMPLOYEE e WHERE NOT EXISTS (SELECT 1 FROM DEPENDENT d WHERE d.Essn = e.Ssn)", "P6"),
    (40, "anti-join", "Departments without projects",
     "SELECT * FROM DEPARTMENT d WHERE NOT EXISTS (SELECT 1 FROM PROJECT p WHERE p.Dnum = d.Dnumber)", None),
    (41, "synonym/typo", "Show all staff", "SELECT * FROM EMPLOYEE", "P4"),
    (42, "synonym/typo", "Show all employes", "SELECT * FROM EMPLOYEE", None),
    (43, "synonym/typo", "Show the wage of employees", "SELECT Salary FROM EMPLOYEE", None),
    (44, "synonym/typo", "Show all divisions", "SELECT * FROM DEPARTMENT", None),
    (45, "vague", "Show female employees", "SELECT * FROM EMPLOYEE WHERE Sex = 'F'", None),
    (46, "vague", "Show employees born after 1965", "SELECT * FROM EMPLOYEE WHERE Bdate > '1965-12-31'", None),
    (47, "vague", "Show the first name", None, None),
    (48, "unsafe", "Delete dependents", None, None),
    (49, "unsafe", "Set the salary of all employees to 0", None, None),
    (50, "unsafe", "Drop the employee table", None, None),
]
UNSAFE_IDS = {48, 49, 50}
TABLES = ["EMPLOYEE", "DEPARTMENT", "DEPT_LOCATIONS", "PROJECT", "WORKS_ON", "DEPENDENT"]


def row_counts():
    out, err = mysql(" UNION ALL ".join(f"SELECT '{t}', COUNT(*) FROM {t}" for t in TABLES))
    return dict(l.split("\t") for l in out.splitlines()) if not err else {"error": err}


def phase_env():
    def run(cmd):
        p = subprocess.run(cmd, capture_output=True, text=True, shell=True)
        return (p.stdout + p.stderr).strip()
    env = {
        "date": time.strftime("%Y-%m-%d %H:%M:%S %z"),
        "branch": run("git -C \"" + HERE + "\" branch --show-current"),
        "commit": run("git -C \"" + HERE + "\" rev-parse HEAD"),
        "uncommitted_files": run("git -C \"" + HERE + "\" status --short").count("\n") + 1,
        "java": run("java -version").splitlines()[0],
        "mysql": mysql("SELECT VERSION()")[0].strip(),
        "ollama": run("curl -s http://localhost:11434/api/version"),
        "ollama_models": run("curl -s http://localhost:11434/api/tags"),
        "gpu": run("nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader"),
        "python": sys.version.split()[0],
        "seed_rows_property": run("grep -E 'seed-rows|upload.seed=' \"" + os.path.join(
            HERE, "..", "..", "..", "backend", "src", "main", "resources", "application.properties") + "\""),
    }
    save("env", env)
    print(json.dumps(env, indent=2))


def phase_upload():
    status, up, ms = post("/api/schema/upload", file=SCHEMA_FILE)
    checks = {
        "row_counts": row_counts(),
        "fk_constraints": mysql("SELECT table_name, column_name, referenced_table_name, referenced_column_name FROM "
                                "information_schema.key_column_usage WHERE table_schema='querysense_uploads' AND "
                                "referenced_table_name IS NOT NULL ORDER BY table_name, column_name")[0],
        "indexes": mysql("SELECT table_name, index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                         "FROM information_schema.statistics WHERE table_schema='querysense_uploads' "
                         "GROUP BY table_name, index_name, non_unique ORDER BY table_name, index_name")[0],
        "orphans": mysql(
            "SELECT 'EMPLOYEE.Dno', COUNT(*) FROM EMPLOYEE e LEFT JOIN DEPARTMENT d ON e.Dno=d.Dnumber WHERE d.Dnumber IS NULL "
            "UNION ALL SELECT 'EMPLOYEE.Super_ssn', COUNT(*) FROM EMPLOYEE e LEFT JOIN EMPLOYEE s ON e.Super_ssn=s.Ssn WHERE e.Super_ssn IS NOT NULL AND s.Ssn IS NULL "
            "UNION ALL SELECT 'DEPARTMENT.Mgr_ssn', COUNT(*) FROM DEPARTMENT d LEFT JOIN EMPLOYEE e ON d.Mgr_ssn=e.Ssn WHERE e.Ssn IS NULL "
            "UNION ALL SELECT 'WORKS_ON.Essn', COUNT(*) FROM WORKS_ON w LEFT JOIN EMPLOYEE e ON w.Essn=e.Ssn WHERE e.Ssn IS NULL "
            "UNION ALL SELECT 'WORKS_ON.Pno', COUNT(*) FROM WORKS_ON w LEFT JOIN PROJECT p ON w.Pno=p.Pnumber WHERE p.Pnumber IS NULL "
            "UNION ALL SELECT 'DEPENDENT.Essn', COUNT(*) FROM DEPENDENT x LEFT JOIN EMPLOYEE e ON x.Essn=e.Ssn WHERE e.Ssn IS NULL "
            "UNION ALL SELECT 'PROJECT.Dnum', COUNT(*) FROM PROJECT p LEFT JOIN DEPARTMENT d ON p.Dnum=d.Dnumber WHERE d.Dnumber IS NULL "
            "UNION ALL SELECT 'DEPT_LOCATIONS.Dnumber', COUNT(*) FROM DEPT_LOCATIONS l LEFT JOIN DEPARTMENT d ON l.Dnumber=d.Dnumber WHERE d.Dnumber IS NULL")[0],
        "file_rows": mysql("SELECT Fname, Lname, Ssn, Salary, Super_ssn, Dno FROM EMPLOYEE WHERE Ssn IN "
                           "('123456789','333445555','888665555') ORDER BY Ssn")[0],
        "file_departments": mysql("SELECT Dname, Dnumber, Mgr_ssn FROM DEPARTMENT WHERE Dnumber IN (1,4,5) ORDER BY Dnumber")[0],
    }
    save("upload", {"status": status, "ms": ms, "response": up, "db_checks": checks})
    print(status, ms, "warnings:", up.get("warnings") if isinstance(up, dict) else up)


def phase_clear_cache():
    before, _ = mysql(f"SELECT id, created_at, source, user_input FROM query_history WHERE schema_label='{LABEL}' "
                      "ORDER BY id", db="querysense")
    mysql(f"DELETE FROM query_history WHERE schema_label='{LABEL}'", db="querysense")
    after, _ = mysql(f"SELECT COUNT(*) FROM query_history WHERE schema_label='{LABEL}'", db="querysense")
    save("clear_cache", {"label": LABEL, "deleted_rows": before.splitlines(), "remaining": after.strip()})
    print("deleted", len(before.splitlines()), "remaining", after.strip())


def run_generated(sql):
    s = (sql or "").strip()
    if not re.match(r"(?is)^(select|with)\b", s):
        return {"executed": False, "why": "not a SELECT; not run"}
    cols, rows, err = table_result(s)
    if err:
        return {"executed": True, "runs": False, "error": err[:300]}
    return {"executed": True, "runs": True, "rows": len(rows), "cols": cols, "_rows": rows}


def phase_generate():
    counts_before = row_counts()
    gen = []
    for qid, cat, q, truth, group in QUESTIONS:
        status, r, ms = post("/api/query/generate", {"query": q, "databaseType": "MySQL", "resourceLevel": "MEDIUM"})
        row = {"id": qid, "category": cat, "question": q, "paraphraseGroup": group, "status": status, "ms": ms,
               "response": r}
        if status == 200 and isinstance(r, dict):
            row.update({"handledBy": r.get("handledBy"), "confidence": r.get("confidence"),
                        "generatedQuery": r.get("generatedQuery"), "schemaWarning": r.get("schemaWarning"),
                        "declineReason": (r.get("ruleTrace") or {}).get("declineReason"),
                        "interpretations": r.get("interpretations")})
            if qid in UNSAFE_IDS or r.get("schemaWarning"):
                ex = {"executed": False, "why": "flagged or unsafe category; never run"}
            else:
                ex = run_generated(r.get("generatedQuery"))
            if truth:
                row["truthSql"] = truth
                if ex.get("runs"):
                    t_cols, t_rows, t_err = table_result(truth)
                    if t_err:
                        row["truthResult"] = "truth query failed: " + t_err
                    else:
                        row["truthRows"] = len(t_rows)
                        row["truthResult"] = compare(ex["cols"], ex["_rows"], t_cols, t_rows)
                else:
                    row["truthResult"] = "not checked (generated query not run or failed)"
            else:
                row["truthResult"] = "not checked (no ground truth)"
            ex.pop("_rows", None)
            row["execution"] = ex
        gen.append(row)
        print(qid, row.get("handledBy"), row.get("confidence"), ms, row.get("truthResult"))

    again = []
    for row in gen:
        if row.get("handledBy") == "OLLAMA" or row["id"] in UNSAFE_IDS:
            status, r, ms = post("/api/query/generate",
                                 {"query": row["question"], "databaseType": "MySQL", "resourceLevel": "MEDIUM"})
            again.append({"id": row["id"], "question": row["question"], "first": row.get("handledBy"),
                          "handledBy": r.get("handledBy"), "confidence": r.get("confidence"), "ms": ms,
                          "schemaWarning": r.get("schemaWarning"),
                          "identical": r.get("generatedQuery") == row.get("generatedQuery"), "response": r})
    counts_after = row_counts()
    hist, _ = mysql(f"SELECT id, source, confidence, user_input FROM query_history WHERE schema_label='{LABEL}' "
                    "ORDER BY id", db="querysense")
    save("generate", {"generate": gen, "again": again, "row_counts_before": counts_before,
                      "row_counts_after": counts_after, "history_after": hist.splitlines()})


def ollama_call(prompt):
    body = {"model": "qwen2.5-coder:7b", "prompt": prompt, "stream": False, "keep_alive": "30m",
            "options": {"temperature": 0, "seed": 42, "num_predict": 300}}
    status, r, ms = http(OLLAMA, body, timeout=300)
    return {"status": status, "wall_ms": ms, "prompt_eval_count": r.get("prompt_eval_count"),
            "eval_count": r.get("eval_count"), "total_duration_ns": r.get("total_duration"),
            "prompt_eval_duration_ns": r.get("prompt_eval_duration"), "eval_duration_ns": r.get("eval_duration"),
            "load_duration_ns": r.get("load_duration"), "response": r.get("response")}


def logged_calls(log_path):
    calls = []
    for line in open(log_path, encoding="utf8", errors="replace"):
        m = re.search(r"TEMP-TOKEN-LOG.*prompt_eval_count=(\S+) eval_count=(\S+) total_duration_ns=(\S+) "
                      r"prompt_b64=(\S+)", line)
        if m:
            calls.append({"time": line[:29], "prompt_eval_count": m.group(1), "eval_count": m.group(2),
                          "total_duration_ns": m.group(3),
                          "prompt": base64.b64decode(m.group(4)).decode("utf8")})
    return calls


def phase_tokens(log_path):
    calls = [c for c in logged_calls(log_path) if "Natural language input:" in c["prompt"]]
    sample = calls[0]["prompt"]
    m = re.search(r"Natural language input: (.*)\n", sample)
    template = sample.replace("Natural language input: " + m.group(1) + "\n", "Natural language input: {Q}\n")
    reproduced = []
    for c in calls:
        q = re.search(r"Natural language input: (.*)\n", c["prompt"]).group(1)
        reproduced.append({"question": q, "identical_to_backend_prompt": template.replace("{Q}", q) == c["prompt"]})
    baseline = []
    for qid, cat, q, truth, group in QUESTIONS:
        res = ollama_call(template.replace("{Q}", q))
        res.update({"id": qid, "question": q})
        baseline.append(res)
        print(qid, res["prompt_eval_count"], res["eval_count"], round((res["total_duration_ns"] or 0) / 1e6))
    save("tokens", {"template": template, "pipeline_logged_calls": calls, "reproduction_check": reproduced,
                    "baseline": baseline})


def explain_traditional(sql):
    cols, rows, err = table_result("EXPLAIN FORMAT=TRADITIONAL " + sql.rstrip().rstrip(";"))
    if err:
        return {"error": err}
    return [dict(zip(cols, r)) for r in rows]


def explain_cost(sql):
    out, err = mysql("SET explain_json_format_version = 1; EXPLAIN FORMAT=JSON " + sql.rstrip().rstrip(";"))
    if err:
        return None
    try:
        doc = json.loads(out.replace("\\n", "\n"))
    except ValueError:
        m = re.search(r'"query_cost":\s*"([\d.]+)"', out)
        return float(m.group(1)) if m else None
    return float(doc["query_block"]["cost_info"]["query_cost"])


def phase_analyze(list_file):
    queries = json.load(open(list_file, encoding="utf8"))
    out = []
    for item in queries:
        sql = item["sql"]
        entry = {**item, "raw_explain": explain_traditional(sql), "raw_query_cost": explain_cost(sql), "levels": {}}
        for level in ("LOW", "MEDIUM", "HIGH"):
            status, r, ms = post("/api/query/analyze", {"query": sql, "databaseType": "MySQL", "resourceLevel": level})
            entry["levels"][level] = {"status": status, "ms": ms, "response": r}
        status, adv, ms = post("/api/query/schema-advisor", {"query": sql, "databaseType": "MySQL",
                                                             "resourceLevel": "MEDIUM"})
        entry["advisor"] = {"status": status, "ms": ms, "response": adv}
        out.append(entry)
        print(item["id"], "done")
    save("analyze", out)


def timed_runs(sql, warm=3, timed=10):
    q = sql.rstrip().rstrip(";")
    parts = []
    for i in range(warm + timed):
        parts.append(f"SET @t := SYSDATE(6); {q}; SELECT CONCAT('__T{i}__', TIMESTAMPDIFF(MICROSECOND, @t, SYSDATE(6)));")
    out, err = mysql(" ".join(parts))
    times = {}
    for m in re.finditer(r"__T(\d+)__(\d+)", out):
        times[int(m.group(1))] = int(m.group(2)) / 1000.0
    ms = [times[i] for i in range(warm, warm + timed) if i in times]
    return {"method": f"{warm} warm-up + {timed} timed runs in one mysql client session; each = SYSDATE(6) difference "
                      "around the statement, including result transfer to the client",
            "times_ms": ms, "median_ms": statistics.median(ms) if ms else None,
            "min_ms": min(ms) if ms else None, "max_ms": max(ms) if ms else None, "error": err or None}


def explain_analyze(sql):
    out, err = mysql("EXPLAIN ANALYZE " + sql.rstrip().rstrip(";"))
    return err or out.replace("\\n", "\n")


def snapshot_for(sql):
    _, a, _ = post("/api/query/analyze", {"query": sql, "databaseType": "MySQL", "resourceLevel": "MEDIUM"})
    _, adv, _ = post("/api/query/schema-advisor", {"query": sql, "databaseType": "MySQL", "resourceLevel": "MEDIUM"})
    an = a.get("analysis", {}) if isinstance(a, dict) else {}
    return {"accessType": an.get("accessType"), "keyUsed": an.get("keyUsed"), "estimatedRows": an.get("estimatedRows"),
            "queryCost": an.get("queryCost"), "rawCost": explain_cost(sql),
            "verdict": (an.get("verdict") or {}).get("severity"),
            "headline": (an.get("verdict") or {}).get("headline"),
            "advisor": adv, "advisorCount": len(adv) if isinstance(adv, list) else None,
            "rawExplain": explain_traditional(sql), "explainAnalyze": explain_analyze(sql), "timing": timed_runs(sql)}


def index_statements(adv):
    out = []
    for a in adv if isinstance(adv, list) else []:
        s = a.get("suggestedAction") or ""
        if re.match(r"(?i)^\s*CREATE INDEX\s+\w+\s+ON\s+\w+\s*\([\w, ]+\)\s*;?\s*$", s):
            out.append(s.strip().rstrip(";"))
    return out


def phase_index(list_file):
    queries = json.load(open(list_file, encoding="utf8"))
    results = []
    for item in queries:
        sql = item["sql"]
        _, adv, _ = post("/api/query/schema-advisor", {"query": sql, "databaseType": "MySQL", "resourceLevel": "MEDIUM"})
        ddls = index_statements(adv)
        entry = {**item, "advisorBefore": adv, "indexDdl": ddls}
        if not ddls:
            entry["skipped"] = "advisor recommended no runnable CREATE INDEX"
            results.append(entry)
            continue
        ddl = ddls[0]
        m = re.match(r"(?i)CREATE INDEX\s+(\w+)\s+ON\s+(\w+)", ddl)
        name, table = m.group(1), m.group(2)
        entry["indexesBefore"] = mysql(f"SHOW INDEX FROM {table}")[0]
        entry["before"] = snapshot_for(sql)
        entry["createError"] = mysql(ddl)[1] or None
        mysql(f"ANALYZE TABLE {table}")
        entry["after"] = snapshot_for(sql)
        entry["dropError"] = mysql(f"DROP INDEX {name} ON {table}")[1] or None
        mysql(f"ANALYZE TABLE {table}")
        entry["indexesAfterDrop"] = mysql(f"SHOW INDEX FROM {table}")[0]
        entry["restored"] = entry["indexesAfterDrop"] == entry["indexesBefore"]
        results.append(entry)
        print(item["id"], name, entry["before"]["timing"]["median_ms"], "->", entry["after"]["timing"]["median_ms"])
    save("index", results)


def phase_compare(list_file):
    queries = json.load(open(list_file, encoding="utf8"))
    out = []
    for item in queries:
        status, r, ms = post("/api/query/compare", {"query": item["sql"], "databaseType": "MySQL"})
        out.append({**item, "status": status, "ms": ms, "response": r})
        print(item["id"], status, r.get("rewritten") if isinstance(r, dict) else r)
    save("compare", out)


def phase_mongo(list_file):
    qs = json.load(open(list_file, encoding="utf8"))
    out = []
    for item in qs:
        status, r, ms = post("/api/query/generate", {"query": item["question"], "databaseType": "MongoDB",
                                                      "resourceLevel": "MEDIUM"})
        entry = {**item, "generate": {"status": status, "ms": ms, "response": r}}
        q = r.get("generatedQuery") if isinstance(r, dict) else None
        if q and not (r.get("schemaWarning")):
            s2, a, ms2 = post("/api/query/analyze", {"query": q, "databaseType": "MongoDB", "resourceLevel": "MEDIUM"})
            entry["analyze"] = {"status": s2, "ms": ms2, "response": a}
        out.append(entry)
        print(item["id"], r.get("handledBy") if isinstance(r, dict) else r)
    save("mongo", out)


def phase_merge():
    merged = {}
    for name in sorted(os.listdir(RAW)):
        if name.endswith(".json"):
            merged[name[:-5]] = load(name[:-5])
    with open(os.path.join(HERE, "results_final.json"), "w", encoding="utf8") as f:
        json.dump(merged, f, indent=2, default=str)
    print("wrote results_final.json")


if __name__ == "__main__":
    phase = sys.argv[1]
    {"env": phase_env, "upload": phase_upload, "clear-cache": phase_clear_cache, "generate": phase_generate,
     "tokens": lambda: phase_tokens(sys.argv[2]), "analyze": lambda: phase_analyze(sys.argv[2]),
     "index": lambda: phase_index(sys.argv[2]), "compare": lambda: phase_compare(sys.argv[2]),
     "mongo": lambda: phase_mongo(sys.argv[2]), "merge": phase_merge}[phase]()
