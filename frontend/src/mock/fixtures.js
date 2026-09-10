/**
 * Mock fixtures for every endpoint in src/api/contracts.js.
 *
 * Shapes here match the REAL backend responses exactly (verified by
 * running it live), not just the synopsis — so a component never needs to
 * know whether it's rendering mock or real data. Where the real backend
 * has no equivalent at all (the Comparison Engine), this is the only
 * source of that data, mock mode or not.
 */

const COMPLEXITY_SIGNALS = [
  /\bjoin\b/i,
  /\bgroup\b/i,
  /\bcompare|comparison|versus|vs\.?\b/i,
  /\blast\s+(week|month|quarter|year)\b/i,
  /\bmore than|less than|at least|at most\b/i,
  /\btop\s+\d+\b/i,
  /\bpercentage|ratio|average|median\b/i,
];

function scoreComplexity(nlQuery) {
  return COMPLEXITY_SIGNALS.reduce((n, re) => (re.test(nlQuery) ? n + 1 : n), 0);
}

// Matches the real schema exactly (customers, orders — plus the internal
// query_history table, which is never a target for NL query generation).
// An earlier version of this list included tables ('products', 'users',
// 'payments', 'inventory') that don't actually exist in the backend —
// harmless while mock mode guesses freely, but worth keeping honest since
// the real rule engine and Gemini prompt are both grounded in this exact
// same two-table schema (see backend KnownSchema.java).
function guessTable(nlQuery) {
  const known = ['customers', 'orders'];
  const hit = known.find((t) => nlQuery.toLowerCase().includes(t.slice(0, -1)));
  return hit || 'customers';
}

function extractColumn(nlQuery) {
  const m = nlQuery.match(/\bby\s+([a-z_]+)\b/i) || nlQuery.match(/\bwhere\s+([a-z_]+)\b/i);
  return m ? m[1].toLowerCase() : 'city';
}

export function mockBuildQuery({ naturalLanguageQuery, targetDb }) {
  const complexity = scoreComplexity(naturalLanguageQuery);
  const handledBy = complexity >= 2 ? 'OLLAMA' : 'RULE_ENGINE';
  const confidence = handledBy === 'RULE_ENGINE' ? 0.95 : 0.65;
  const table = guessTable(naturalLanguageQuery);
  const column = extractColumn(naturalLanguageQuery);
  const lastWord = naturalLanguageQuery.trim().split(/\s+/).slice(-1)[0].replace(/[^\w@.]/g, '');

  const generatedQuery = targetDb === 'MONGODB'
    ? `db.${table}.find({ ${column}: "${lastWord}" }).limit(20)`
    : `SELECT *\nFROM ${table}\nWHERE ${column} = '${lastWord}'\nORDER BY ${column}\nLIMIT 20;`;

  return { generatedQuery, targetDb, handledBy, confidence };
}

/**
 * Every column that's actually indexed in the real dev DB, across both
 * real tables — `customers`(id, name, city, email) once schema-advisor
 * work landed, and `orders`(order_id PK, customer_id FK; order_date/
 * amount/status are NOT indexed) now that orders exists. A genuine
 * full-scan in the mock only shows up when asked about a column outside
 * this set.
 */
const KNOWN_INDEXED_COLUMNS = ['id', 'name', 'city', 'email', 'order_id', 'customer_id'];

export function mockAnalyzeQuery({ query, targetDb, domain, resourceLevel }) {
  const table = guessTable(query);

  if (targetDb === 'MONGODB') {
    const hasUnindexedFilter = !/_id|email|userId/i.test(query);
    const hasUnanchoredRegex = /\$regex\s*:\s*"[^^]/.test(query);
    const filteredColumn = (query.match(/(\w+)\s*:/) || [])[1] || null;

    const analysis = {
      tableName: null,
      queryType: 'FIND',
      databaseType: targetDb,
      usesIndex: !hasUnindexedFilter,
      hasFullTableScan: false,
      usesFilesort: false,
      usesTemporaryTable: false,
      hasUnindexedFilter,
      hasUnanchoredRegex,
      filteredColumn,
      orderByColumn: null,
      explainFailed: false, // MySQL-only concept — not applicable to a Mongo result
      warnings: [
        hasUnindexedFilter && `Filtering on '${filteredColumn}' with no known index — this may cause a full collection scan.`,
        hasUnanchoredRegex && "Unanchored $regex pattern detected — prefix with '^' to allow index usage, if an index exists on this field.",
      ].filter(Boolean),
    };

    const suggestions = [];
    if (hasUnindexedFilter) {
      suggestions.push({
        message: `Filter on '${filteredColumn}' has no supporting index — every matching query forces a full collection scan. Add an index on the filtered field(s) as a priority.`,
        severity: resourceLevel === 'LOW' ? 'CRITICAL' : 'WARNING',
        source: 'DOMAIN',
      });
    }
    if (hasUnanchoredRegex) {
      suggestions.push({
        message: 'Unanchored regex filter detected. This pattern cannot use an index — consider anchoring with ^ or switching to a text index.',
        severity: 'WARNING',
        source: 'DOMAIN',
      });
    }
    return { analysis, suggestions };
  }

  const column = extractColumn(query);
  const orderByMatch = query.match(/order by\s+([a-z_]+)/i);
  const orderByColumn = orderByMatch ? orderByMatch[1].toLowerCase() : null;
  const columnIsKnownIndexed = KNOWN_INDEXED_COLUMNS.includes(column);
  // The rule-based builder always emits SELECT * with no projection — same
  // signal the real EXPLAIN test in CLAUDE.md showed as accessType ALL.
  const hasFullTableScan = /select\s+\*/i.test(query) && !columnIsKnownIndexed;
  // Honest finding from the real verification: MySQL can only use one index
  // per table access, so filtering on one indexed column while ordering by a
  // different one still triggers filesort even when both are individually indexed.
  const usesFilesort = Boolean(orderByColumn) && orderByColumn !== column && columnIsKnownIndexed;
  const usesTemporaryTable = /group by/i.test(query) && !columnIsKnownIndexed;
  const estimatedRows = hasFullTableScan ? 303 : 4;

  const analysis = {
    tableName: table,
    queryType: /delete/i.test(query) ? 'DELETE' : 'SELECT',
    databaseType: targetDb,
    estimatedRows,
    accessType: hasFullTableScan ? 'ALL' : 'ref',
    usesIndex: !hasFullTableScan,
    hasFullTableScan,
    usesFilesort,
    usesTemporaryTable,
    keyUsed: hasFullTableScan ? null : `idx_${table}_${column}`,
    hasUnindexedFilter: false,
    hasUnanchoredRegex: false,
    filteredColumn: column,
    orderByColumn,
    // Mock queries are always well-formed by construction, so EXPLAIN never
    // "fails" here — this just keeps the shape identical to the real
    // AnalyzeResponse so ExplainPlanView doesn't need to special-case mock
    // mode.
    explainFailed: false,
    warnings: [
      hasFullTableScan && `Full table scan on ${table} — no index covers ${column}.`,
      usesFilesort && 'Query requires an extra sorting pass (filesort) — an index matching the ORDER BY clause could avoid this.',
      usesTemporaryTable && 'Query uses GROUP BY — check EXPLAIN output for \'Using temporary\' if this feels slow.',
    ].filter(Boolean),
  };

  const rowsAreSignificant = estimatedRows > 1000;
  const suggestions = [];

  if (hasFullTableScan) {
    suggestions.push({
      message: rowsAreSignificant
        ? `Full table scan detected on '${table}' over an estimated ${estimatedRows} rows. Add an index on the filtered column(s).`
        : `Full table scan detected on '${table}', but the estimated row count is low, so the performance impact is likely minor for now.`,
      severity: rowsAreSignificant ? 'CRITICAL' : 'INFO',
      source: 'DOMAIN',
    });
  }
  if (usesFilesort) {
    suggestions.push({
      message: "Query requires a filesort. Consider a composite index covering both the filter and sort columns to avoid this.",
      severity: 'WARNING',
      source: 'DOMAIN',
    });
  }
  if (usesTemporaryTable) {
    suggestions.push({
      message: 'Query uses a temporary table, likely from a GROUP BY without index support. Consider a covering index or a precomputed summary table.',
      severity: 'WARNING',
      source: 'DOMAIN',
    });
  }

  // Resource-level severity rescaling, mirroring ResourceConsciousService.adjustSeverity:
  // escalate under LOW, unchanged under MEDIUM, de-escalate under HIGH.
  const severityOrder = ['INFO', 'WARNING', 'CRITICAL'];
  const shift = resourceLevel === 'LOW' ? 1 : resourceLevel === 'HIGH' ? -1 : 0;
  const rescaled = suggestions.map((s) => ({
    ...s,
    severity: severityOrder[Math.max(0, Math.min(2, severityOrder.indexOf(s.severity) + shift))],
  }));

  if (rowsAreSignificant && usesFilesort && resourceLevel !== 'HIGH') {
    rescaled.push({
      message: resourceLevel === 'LOW'
        ? 'Available memory is low and this filesort covers a significant row count — it is likely to spill to disk.'
        : 'Filesort over a significant row count. At medium available memory this is usually fine, but worth monitoring.',
      severity: resourceLevel === 'LOW' ? 'CRITICAL' : 'WARNING',
      source: 'RESOURCE',
    });
  }

  return { analysis, suggestions: rescaled };
}

export function mockSchemaAdvise({ query, targetDb, domain, resourceLevel }) {
  const { analysis } = mockAnalyzeQuery({ query, targetDb, domain, resourceLevel });
  const recommendations = [];

  if (analysis.hasFullTableScan && !analysis.usesIndex) {
    recommendations.push({
      type: 'INDEX',
      targetTable: analysis.tableName,
      targetColumn: analysis.filteredColumn,
      reason: `Full table scan detected (accessType=ALL) with no index in use on '${analysis.filteredColumn}'.`,
      suggestedAction: `CREATE INDEX idx_${analysis.tableName}_${analysis.filteredColumn} ON ${analysis.tableName}(${analysis.filteredColumn});`,
    });
  }
  if (analysis.usesFilesort) {
    const filterCol = analysis.filteredColumn;
    const sortCol = analysis.orderByColumn;
    recommendations.push({
      type: 'INDEX',
      targetTable: analysis.tableName,
      targetColumn: sortCol,
      reason: `Query requires an extra sorting pass for ORDER BY '${sortCol}'. Note: this query also filters on '${filterCol}' — MySQL can only use one index per table access, so a composite index on (${filterCol}, ${sortCol}) may be needed instead. Verify with EXPLAIN after applying.`,
      suggestedAction: `CREATE INDEX idx_${analysis.tableName}_${sortCol} ON ${analysis.tableName}(${sortCol});`,
    });
  }
  if (analysis.usesTemporaryTable) {
    recommendations.push({
      type: 'STRUCTURAL',
      targetTable: analysis.tableName,
      targetColumn: null,
      reason: "Query requires a temporary table, typically caused by GROUP BY or DISTINCT without a supporting index.",
      suggestedAction: `Review the GROUP BY/DISTINCT clause on '${analysis.tableName}'; consider a composite index covering the grouped column(s).`,
    });
  }
  if (analysis.hasUnindexedFilter) {
    recommendations.push({
      type: 'INDEX',
      targetTable: analysis.tableName,
      targetColumn: analysis.filteredColumn,
      reason: `Filtering on '${analysis.filteredColumn}' with no known index — likely a full collection scan.`,
      suggestedAction: `db.${analysis.tableName || '<collection>'}.createIndex({ "${analysis.filteredColumn}": 1 });`,
    });
  }
  if (analysis.hasUnanchoredRegex) {
    recommendations.push({
      type: 'STRUCTURAL',
      targetTable: analysis.tableName,
      targetColumn: null,
      reason: 'Unanchored $regex pattern detected — MongoDB cannot use an index for a non-left-anchored regex.',
      suggestedAction: "Rewrite the regex with a leading '^', or create a text index if full substring search is required.",
    });
  }

  return recommendations;
}

export function mockCompare({ originalAnalysis, optimizedAnalysis, changes = [] }) {
  const before = originalAnalysis?.estimatedRows ?? 303;
  const after = optimizedAnalysis?.estimatedRows ?? 4;
  return {
    estimatedCostDelta: after - before,
    isEstimate: true,
    // Every line the before/after diff can show (index usage, column
    // pruning, LIMIT) has a matching entry here — `changes` carries whatever
    // structural edits mockSimulateOptimizedQuery actually made, so nothing
    // appears in the diff without a citation explaining it.
    ruleTrace: [
      { ruleId: 'EXPLAIN-01', explanation: 'EXPLAIN showed accessType ALL before optimization — full table scan detected.' },
      { ruleId: 'SCHEMA-01', explanation: 'Schema Advisor recommended an index on the filtered column.' },
      { ruleId: 'EXPLAIN-02', explanation: 'Post-index EXPLAIN shows accessType ref with a matching key — scan replaced by index lookup.' },
      ...changes,
    ],
    summary: `Estimated rows examined dropped from ${before} to ${after} after applying the recommended index. This is an EXPLAIN-based estimate, not a measured runtime.`,
  };
}

/** Used only by the Optimization page's simulated Comparison Engine preview — no real backend equivalent exists yet. */
const DOMAIN_RULES = {
  ECOMMERCE: [
    { ruleId: 'ECOM-01', description: 'Limit result set for product listing pages to avoid loading full catalogs.' },
    { ruleId: 'ECOM-04', description: 'Prefer covering index on (category, price) for filtered catalog browsing.' },
  ],
  ANALYTICAL: [
    { ruleId: 'ANLY-02', description: 'Push aggregation down before joins to reduce intermediate row counts.' },
    { ruleId: 'ANLY-05', description: 'Materialize repeated subqueries used across the same report.' },
  ],
  TRANSACTIONAL: [
    { ruleId: 'TXN-01', description: 'Avoid SELECT * on hot transactional tables — project only required columns.' },
    { ruleId: 'TXN-03', description: 'Ensure WHERE clause hits the primary key or a unique index for point lookups.' },
  ],
  OTHER: [
    { ruleId: 'GEN-01', description: 'Project only the columns actually needed instead of SELECT *.' },
    { ruleId: 'GEN-02', description: 'Cap unbounded result sets with a LIMIT clause.' },
  ],
};

export function mockSimulateOptimizedQuery({ generatedQuery, tableName, domain }) {
  const rulesApplied = DOMAIN_RULES[domain] || DOMAIN_RULES.OTHER;
  const table = tableName || 'customers';

  // Every value here is derived from the REAL query being analyzed, never
  // hardcoded — a previous version of this mock hardcoded WHERE city =
  // 'Mumbai' regardless of input, which silently changed the filter value's
  // case (a semantic change, not an optimization) whenever the original
  // query used different casing.
  const whereMatch = generatedQuery.match(/where\s+([a-z_]+)\s*=\s*('[^']*'|"[^"]*"|[\w.]+)/i);
  const whereClause = whereMatch ? `${whereMatch[1]} = ${whereMatch[2]}` : null;
  const filteredColumn = whereMatch ? whereMatch[1].toLowerCase() : null;

  const hasSelectStar = /select\s+\*/i.test(generatedQuery);
  const hasExistingLimit = /\blimit\s+\d+/i.test(generatedQuery);

  // Only prune/cap the query when a rule actually IN rulesApplied justifies
  // that specific change, and only ever prune to a column the original
  // query itself references (the filtered column) — never invented ones.
  // This keeps every line in the before/after diff traceable to something
  // named in "Why it would change" (wired below via `changes`).
  const projectionRule = rulesApplied.find((r) => r.ruleId === 'TXN-01' || r.ruleId === 'GEN-01');
  const limitRule = rulesApplied.find((r) => r.ruleId === 'ECOM-01' || r.ruleId === 'GEN-02');

  const changes = [];
  let selectClause = '*';
  if (projectionRule && hasSelectStar && filteredColumn) {
    selectClause = filteredColumn;
    changes.push({
      ruleId: projectionRule.ruleId,
      explanation: `Projected only the column this query actually filters on ('${filteredColumn}') instead of SELECT * — per ${projectionRule.ruleId}.`,
    });
  }

  let limitClause = '';
  if (limitRule && !hasExistingLimit) {
    limitClause = '\nLIMIT 20';
    changes.push({
      ruleId: limitRule.ruleId,
      explanation: `Added a LIMIT to cap an otherwise unbounded result set — per ${limitRule.ruleId}.`,
    });
  }

  // If we couldn't confidently identify a WHERE clause to preserve, don't
  // guess at a rewrite — leave the query unchanged rather than fabricate one.
  const optimizedQuery = whereClause
    ? `SELECT ${selectClause}\nFROM ${table}\nWHERE ${whereClause}${limitClause};`
    : generatedQuery;

  return { optimizedQuery, rulesApplied, domain, changes };
}

let historyIdCounter = 1000;
let historyStore = [
  {
    id: ++historyIdCounter,
    userInput: 'show customers from Mumbai',
    generatedQuery: "SELECT * FROM customers WHERE city = 'mumbai';",
    optimizedQuery: null,
    databaseType: 'MySQL',
    source: 'RULE_ENGINE',
    createdAt: new Date(Date.now() - 1000 * 60 * 42).toISOString(),
  },
  {
    id: ++historyIdCounter,
    userInput: 'compare average order value between last month and this month for premium customers',
    generatedQuery: 'SELECT ... /* LLM-generated, comparison + time expression */',
    optimizedQuery: null,
    databaseType: 'MySQL',
    // Deliberately left as the pre-rename value: real /history still serves
    // GEMINI rows, so mock mode exercises that legacy branch too.
    source: 'GEMINI',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 5).toISOString(),
  },
  {
    id: ++historyIdCounter,
    userInput: 'find users with unverified email',
    generatedQuery: 'db.users.find({ emailVerified: false });',
    optimizedQuery: null,
    databaseType: 'MongoDB',
    source: 'RULE_ENGINE',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 26).toISOString(),
  },
];

export function mockGetHistory() {
  return { records: historyStore };
}

export function mockPostHistory(entry) {
  const record = { id: ++historyIdCounter, optimizedQuery: null, createdAt: new Date().toISOString(), ...entry };
  historyStore = [record, ...historyStore];
  return record;
}

// ---------------------------------------------------------------------------
// Schema source (GET /api/schema/active, POST /upload, POST /reset)
// ---------------------------------------------------------------------------

// The built-in schema, matching the backend's KnownSchema table for table.
// rowCount is 0 because the real GET /active reports 0 for the demo tables —
// nothing measures them at startup. Inventing a number here would make mock
// mode disagree with the backend on a field the UI renders.
const BUILT_IN_TABLES = [
  { name: 'categories', columns: ['id', 'name'], rowCount: 0 },
  { name: 'customers', columns: ['id', 'name', 'email', 'city', 'phone'], rowCount: 0 },
  { name: 'departments', columns: ['id', 'name'], rowCount: 0 },
  { name: 'employees', columns: ['id', 'name', 'email', 'department_id', 'manager_id', 'hire_date'], rowCount: 0 },
  { name: 'order_items', columns: ['id', 'order_id', 'product_id', 'quantity', 'unit_price'], rowCount: 0 },
  { name: 'orders', columns: ['id', 'customer_id', 'employee_id', 'order_date', 'status'], rowCount: 0 },
  { name: 'payments', columns: ['id', 'order_id', 'amount', 'payment_date', 'method'], rowCount: 0 },
  { name: 'products', columns: ['id', 'name', 'price', 'category_id', 'supplier_id'], rowCount: 0 },
  { name: 'suppliers', columns: ['id', 'name', 'contact_email'], rowCount: 0 },
];

const BUILT_IN_FOREIGN_KEYS = [
  { fromTable: 'employees', fromColumn: 'department_id', toTable: 'departments', toColumn: 'id' },
  { fromTable: 'employees', fromColumn: 'manager_id', toTable: 'employees', toColumn: 'id' },
  { fromTable: 'order_items', fromColumn: 'order_id', toTable: 'orders', toColumn: 'id' },
  { fromTable: 'order_items', fromColumn: 'product_id', toTable: 'products', toColumn: 'id' },
  { fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: 'id' },
  { fromTable: 'orders', fromColumn: 'employee_id', toTable: 'employees', toColumn: 'id' },
  { fromTable: 'payments', fromColumn: 'order_id', toTable: 'orders', toColumn: 'id' },
  { fromTable: 'products', fromColumn: 'category_id', toTable: 'categories', toColumn: 'id' },
  { fromTable: 'products', fromColumn: 'supplier_id', toTable: 'suppliers', toColumn: 'id' },
];

function builtInSnapshot() {
  return {
    schemaId: 'builtin',
    label: 'QuerySense demo schema (built-in)',
    catalog: null,
    builtIn: true,
    tables: BUILT_IN_TABLES,
    foreignKeys: BUILT_IN_FOREIGN_KEYS,
    warnings: [],
    loadedAt: new Date(0).toISOString(),
  };
}

// Mock mode keeps its own idea of which schema is active, so uploading and
// resetting behave like the real thing across navigations instead of
// snapping back to built-in on every read.
let mockActive = builtInSnapshot();

export function mockActiveSchema() {
  return mockActive;
}

export function mockResetSchema() {
  mockActive = builtInSnapshot();
  return mockActive;
}

/**
 * A deliberately shallow CREATE TABLE reader — enough that the preview
 * reflects what the user actually pasted rather than a canned example. The
 * real parsing is JSqlParser's, on the backend; this exists so mock mode
 * isn't a lie about the one screen whose whole job is showing your schema.
 */
export function mockSchemaUpload({ ddl, label }) {
  const tables = [];
  const pattern = /create\s+table\s+`?(\w+)`?\s*\(([\s\S]*?)\)\s*(?:engine|;|$)/gi;
  let match = pattern.exec(ddl || '');
  while (match) {
    const columns = match[2]
      .split(',')
      .map((line) => line.trim())
      .filter((line) => line && !/^(primary|foreign|unique|key|constraint|index)\b/i.test(line))
      .map((line) => line.replace(/^`?(\w+)`?[\s\S]*$/, '$1'))
      .filter(Boolean);
    if (columns.length) {
      tables.push({ name: match[1], columns, rowCount: 12000 });
    }
    match = pattern.exec(ddl || '');
  }

  mockActive = {
    schemaId: `upload-${Math.random().toString(16).slice(2, 10)}`,
    label: label || 'Uploaded schema',
    catalog: 'querysense_uploads',
    builtIn: false,
    tables,
    foreignKeys: [],
    warnings: tables.length ? [] : ['No CREATE TABLE statements were found.'],
    loadedAt: new Date().toISOString(),
  };
  return mockActive;
}
