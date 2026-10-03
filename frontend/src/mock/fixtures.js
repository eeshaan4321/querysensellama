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

  const ruleTrace = handledBy === 'RULE_ENGINE'
    ? {
        outcome: 'BUILT',
        shape: 'SELECT',
        bindings: [
          { phrase: table, kind: 'TABLE', resolvedTo: table },
          { phrase: column, kind: 'FILTER', resolvedTo: `${table}.${column}` },
        ],
        joinEdge: null,
        checksPassed: ['Every field named in the question matched a column in the active schema'],
        declineReason: null,
      }
    : {
        outcome: 'DECLINED',
        shape: null,
        bindings: [{ phrase: table, kind: 'TABLE', resolvedTo: table }],
        joinEdge: null,
        checksPassed: [],
        declineReason:
          'The question asks about a period of time. This engine does not build date filters, '
          + 'so the question went to the language model instead.',
      };

  return { generatedQuery, targetDb, handledBy, confidence, ruleTrace, schemaWarning: null, interpretations: [] };
}

const KNOWN_INDEXED_COLUMNS = ['id', 'name', 'city', 'email', 'order_id', 'customer_id'];

export function mockAnalyzeQuery({ query, targetDb, resourceLevel }) {
  const table = guessTable(query);

  if (targetDb === 'MONGODB') {
    const collection = (query.match(/db\.(\w+)\./) || [])[1] || 'customers';
    const field = (query.match(/\{\s*"?(\w+)"?\s*:/) || [])[1] || 'city';
    const indexed = /_id|email/i.test(query);
    const examined = indexed ? 1 : 12000;
    const returned = indexed ? 1 : 1040;
    const stage = indexed ? 'EXPRESS_IXSCAN' : 'COLLSCAN';
    const keyUsed = indexed ? 'email_1' : null;

    const analysis = {
      tableName: collection,
      queryType: /aggregate/.test(query) ? 'AGGREGATE' : 'FIND',
      databaseType: 'MongoDB',
      estimatedRows: examined,
      estimatedRowsReturned: returned,
      filtered: indexed ? 100 : 8.7,
      accessType: stage,
      usesIndex: indexed,
      hasFullTableScan: !indexed,
      usesFilesort: false,
      usesTemporaryTable: false,
      keyUsed,
      hasUnindexedFilter: !indexed,
      hasUnanchoredRegex: /\$regex\s*:\s*"[^^]/.test(query),
      filteredColumn: field,
      orderByColumn: null,
      explainFailed: false,
      queryCost: null,
      executionMillis: indexed ? 0 : 9,
      tables: [{
        tableName: collection,
        accessType: stage,
        keyUsed,
        possibleKeys: null,
        estimatedRows: examined,
        estimatedRowsReturned: returned,
        filtered: indexed ? 100 : 8.7,
        fullTableScan: !indexed,
        usesIndex: indexed,
        usesFilesort: false,
        usesTemporaryTable: false,
      }],
      planTree: {
        kind: stage,
        label: keyUsed || collection,
        accessType: stage,
        keyUsed,
        estimatedRows: examined,
        cost: null,
        usesFilesort: false,
        usesTemporaryTable: false,
        children: [],
      },
      warnings: indexed
        ? []
        : [`Collection scan detected on '${collection}'. MongoDB read every document in this collection. If the query is slow, check whether an index on the filtered fields could reduce the documents examined.`],
      verdict: indexed
        ? {
            severity: 'INFO',
            label: 'Looks efficient',
            headline: 'Uses the email_1 index to find documents instead of reading the whole collection.',
            detail: 'About 1 documents were examined.',
          }
        : {
            severity: 'CRITICAL',
            label: 'Needs attention',
            headline: `Reads the whole ${collection} collection — about 12,000 documents — then discards 91.3% of what it read.`,
            detail: 'MongoDB reported no index it could have used for this collection.',
          },
    };

    const suggestions = indexed
      ? []
      : [{
          message: `Collection scan on '${collection}' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.`,
          severity: resourceLevel === 'HIGH' ? 'WARNING' : 'CRITICAL',
          source: 'DOMAIN',
          suggestedChange: `db.${collection}.createIndex({ "${field}": 1 });`,
        }];
    return { analysis, suggestions };
  }

  const column = extractColumn(query);
  const orderByMatch = query.match(/order by\s+([a-z_]+)/i);
  const orderByColumn = orderByMatch ? orderByMatch[1].toLowerCase() : null;
  const columnIsKnownIndexed = KNOWN_INDEXED_COLUMNS.includes(column);
  const hasFullTableScan = /select\s+\*/i.test(query) && !columnIsKnownIndexed;
  const usesFilesort = Boolean(orderByColumn) && orderByColumn !== column && columnIsKnownIndexed;
  const usesTemporaryTable = /group by/i.test(query) && !columnIsKnownIndexed;
  const estimatedRows = hasFullTableScan ? 303 : 4;

  const analysis = {
    tableName: table,
    queryType: /delete/i.test(query) ? 'DELETE' : 'SELECT',
    databaseType: 'MySQL',
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
    explainFailed: false,
    filtered: hasFullTableScan ? 12.5 : 100,
    estimatedRowsReturned: hasFullTableScan ? 38 : 4,
    queryCost: hasFullTableScan ? 31.45 : 1.2,
    planTree: {
      kind: 'query_block',
      label: 'query block',
      cost: hasFullTableScan ? 31.45 : 1.2,
      children: [
        ...(orderByColumn
          ? [{
              kind: 'ordering_operation',
              label: 'ordering operation',
              usesFilesort,
              children: [{
                kind: 'table',
                label: table,
                accessType: hasFullTableScan ? 'ALL' : 'ref',
                keyUsed: hasFullTableScan ? null : `idx_${table}_${column}`,
                estimatedRows,
                children: [],
              }],
            }]
          : [{
              kind: 'table',
              label: table,
              accessType: hasFullTableScan ? 'ALL' : 'ref',
              keyUsed: hasFullTableScan ? null : `idx_${table}_${column}`,
              estimatedRows,
              children: [],
            }]),
      ],
    },
    verdict: hasFullTableScan
      ? {
          severity: 'INFO',
          label: 'Looks efficient',
          headline: `Reads all ${estimatedRows} rows of ${table}, which is normal at this size.`,
          detail:
            'Most of those rows are discarded, but the table is small enough that the cost is negligible.',
        }
      : {
          severity: 'INFO',
          label: 'Looks efficient',
          headline: `Uses the idx_${table}_${column} index to find rows instead of reading the whole table.`,
          detail: `About ${estimatedRows} rows are expected to be read.`,
        },
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

export function mockSchemaAdvise({ query, targetDb, resourceLevel }) {
  const { analysis } = mockAnalyzeQuery({ query, targetDb, resourceLevel });
  const recommendations = [];

  if (analysis.hasFullTableScan && !analysis.usesIndex) {
    recommendations.push({
      type: 'INDEX',
      targetTable: analysis.tableName,
      targetColumn: analysis.filteredColumn,
      summary: `To answer this, the database is reading every row of ${analysis.tableName} and checking each one, because nothing indexes ${analysis.filteredColumn}. An index works like the index at the back of a book: it lets the database jump straight to the matching rows instead of reading everything.`,
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
      summary: `The database can find the rows, but then has to put them in order in a second pass, because nothing keeps ${sortCol} already sorted. This query also narrows by ${filterCol}, and the database can only use one index per table at a time — one index covering both columns together may be what it needs. Re-check after applying rather than assuming it worked.`,
      reason: `Query requires an extra sorting pass for ORDER BY '${sortCol}'. Note: this query also filters on '${filterCol}' — MySQL can only use one index per table access, so a composite index on (${filterCol}, ${sortCol}) may be needed instead. Verify with EXPLAIN after applying.`,
      suggestedAction: `CREATE INDEX idx_${analysis.tableName}_${sortCol} ON ${analysis.tableName}(${sortCol});`,
    });
  }
  if (analysis.usesTemporaryTable) {
    recommendations.push({
      type: 'STRUCTURAL',
      targetTable: analysis.tableName,
      targetColumn: null,
      summary: 'The database is building a scratch working table behind the scenes to combine these rows, which usually means the columns being combined on are not indexed. There is no single statement that reliably fixes this, so it is flagged for a person to look at.',
      reason: "Query requires a temporary table, typically caused by GROUP BY or DISTINCT without a supporting index.",
      suggestedAction: `Review the GROUP BY/DISTINCT clause on '${analysis.tableName}'; consider a composite index covering the grouped column(s).`,
    });
  }
  if (analysis.hasUnindexedFilter) {
    recommendations.push({
      type: 'INDEX',
      targetTable: analysis.tableName,
      targetColumn: analysis.filteredColumn,
      summary: `The database is checking every record in this collection, because nothing indexes ${analysis.filteredColumn}. An index lets it go straight to the matching records instead of reading all of them.`,
      reason: `Filtering on '${analysis.filteredColumn}' with no known index — likely a full collection scan.`,
      suggestedAction: `db.${analysis.tableName || '<collection>'}.createIndex({ "${analysis.filteredColumn}": 1 });`,
    });
  }
  if (analysis.hasUnanchoredRegex) {
    recommendations.push({
      type: 'STRUCTURAL',
      targetTable: analysis.tableName,
      targetColumn: null,
      summary: 'The search pattern can match anywhere inside the text. The database can only take a shortcut when a pattern is pinned to the start of the value, so here it reads every record whether an index exists or not.',
      reason: 'Unanchored $regex pattern detected — MongoDB cannot use an index for a non-left-anchored regex.',
      suggestedAction: "Rewrite the regex with a leading '^', or create a text index if full substring search is required.",
    });
  }

  return recommendations;
}

export function mockCompare({ query, targetDb }) {
  if (targetDb === 'MONGODB') {
    const original = query || 'db.customers.find({ "email": "a@b.c" }).sort({ "name": 1 })';
    const rewritten = /\.sort\(/.test(original) && /email|_id/.test(original);
    const optimized = rewritten ? original.replace(/\.sort\([^)]*\)/, '') : original;
    return {
      originalQuery: original,
      optimizedQuery: optimized,
      rewritten,
      changes: rewritten
        ? [{ ruleId: 'RW3', explanation: "Dropped .sort() because the filter matches 'email' exactly and a unique index on it allows at most one document. Sorting one document cannot change the result; it only cost an in-memory sort." }]
        : [],
      originalAnalysis: { estimatedRows: 1, accessType: 'IXSCAN' },
      optimizedAnalysis: rewritten ? { estimatedRows: 1, accessType: 'EXPRESS_IXSCAN' } : null,
      estimatedRowsDelta: rewritten ? 0 : null,
      queryCostDelta: null,
      estimate: false,
      summary: rewritten
        ? '1 rewrite rule applied. MongoDB examined the same number of documents either way, so the rewrite changes the shape of the query without changing what it reads. The plan changed from IXSCAN to EXPRESS_IXSCAN.'
        : 'No rewrite rule applied to this query, so there is nothing to compare it against.',
    };
  }

  const original = query || "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
  const optimized = original
    .replace(/select\s+\*/i, 'SELECT id, name, email, city, phone')
    .replace(/city\s*=\s*'mumbai'\s+OR\s+city\s*=\s*'pune'/i, "city IN ('mumbai', 'pune')");
  const rewritten = optimized !== original;

  return {
    originalQuery: original,
    optimizedQuery: optimized,
    rewritten,
    changes: rewritten
      ? [
          { ruleId: 'RW1', explanation: "Named the 5 columns of 'customers' instead of SELECT *, so an index covering them can answer the query without reading the row. The rows returned are identical." },
          { ruleId: 'RW2', explanation: "Collapsed 2 OR'd equality tests on 'city' into a single IN list. MySQL can range-scan an index for IN, where a chain of ORs on the same column often falls back to a scan. The rows returned are identical." },
        ]
      : [],
    originalAnalysis: { estimatedRows: 303, queryCost: 62.15 },
    optimizedAnalysis: rewritten ? { estimatedRows: 12, queryCost: 4.65 } : null,
    estimatedRowsDelta: rewritten ? -291 : null,
    queryCostDelta: rewritten ? -57.5 : null,
    estimate: true,
    summary: rewritten
      ? '2 rewrite rules applied. EXPLAIN estimates 291 fewer rows read under the rewrite. That is an estimate from the plan, not a measurement.'
      : 'No rewrite rule applied to this query, so there is nothing to compare it against.',
  };
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
    confidence: 0.95,
    durationMs: 14,
    schemaLabel: 'QuerySense demo schema (built-in)',
    createdAt: new Date(Date.now() - 1000 * 60 * 42).toISOString(),
  },
  {
    id: ++historyIdCounter,
    userInput: 'compare average order value between last month and this month for premium customers',
    generatedQuery: 'SELECT ... /* LLM-generated, comparison + time expression */',
    optimizedQuery: null,
    databaseType: 'MySQL',
    source: 'GEMINI',
    confidence: 0.95,
    durationMs: 14,
    schemaLabel: 'QuerySense demo schema (built-in)',
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 5).toISOString(),
  },
  {
    id: ++historyIdCounter,
    userInput: 'find users with unverified email',
    generatedQuery: 'db.users.find({ emailVerified: false });',
    optimizedQuery: null,
    databaseType: 'MongoDB',
    source: 'RULE_ENGINE',
    confidence: 0.95,
    durationMs: 14,
    schemaLabel: 'QuerySense demo schema (built-in)',
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
].map((table) => ({ ...table, embeddedArrays: [], fieldTypes: {} }));

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

function collection(name, fieldTypes, embeddedArrays = []) {
  return { name, columns: Object.keys(fieldTypes), rowCount: 0, embeddedArrays, fieldTypes };
}

const MONGO_BUILT_IN_TABLES = [
  collection('categories', { _id: 'objectId', name: 'string' }),
  collection('customers', { _id: 'objectId', name: 'string', email: 'string', city: 'string', phone: 'string' }),
  collection('departments', { _id: 'objectId', name: 'string' }),
  collection('employees', {
    _id: 'objectId', name: 'string', email: 'string', department_id: 'objectId', manager_id: 'objectId', hire_date: 'date',
  }),
  collection('orders', {
    _id: 'objectId', customer_id: 'objectId', employee_id: 'objectId', order_date: 'date', status: 'string',
    'items.product_id': 'objectId', 'items.quantity': 'int', 'items.unit_price': 'decimal',
  }, ['items']),
  collection('payments', { _id: 'objectId', order_id: 'objectId', amount: 'decimal', payment_date: 'date', method: 'string' }),
  collection('products', { _id: 'objectId', name: 'string', price: 'decimal', category_id: 'objectId', supplier_id: 'objectId' }),
  collection('suppliers', { _id: 'objectId', name: 'string', contact_email: 'string' }),
];

const MONGO_BUILT_IN_REFERENCES = [
  { fromTable: 'employees', fromColumn: 'department_id', toTable: 'departments', toColumn: '_id' },
  { fromTable: 'employees', fromColumn: 'manager_id', toTable: 'employees', toColumn: '_id' },
  { fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: '_id' },
  { fromTable: 'orders', fromColumn: 'employee_id', toTable: 'employees', toColumn: '_id' },
  { fromTable: 'orders', fromColumn: 'items.product_id', toTable: 'products', toColumn: '_id' },
  { fromTable: 'payments', fromColumn: 'order_id', toTable: 'orders', toColumn: '_id' },
  { fromTable: 'products', fromColumn: 'category_id', toTable: 'categories', toColumn: '_id' },
  { fromTable: 'products', fromColumn: 'supplier_id', toTable: 'suppliers', toColumn: '_id' },
];

function index(table, column, unique = false) {
  return { table, keys: [{ column, direction: 1 }], unique };
}

const MONGO_BUILT_IN_INDEXES = [
  index('customers', 'email', true),
  index('employees', 'email', true),
  index('employees', 'department_id'),
  index('employees', 'manager_id'),
  index('orders', 'customer_id'),
  index('orders', 'employee_id'),
  index('orders', 'items.product_id'),
  index('payments', 'order_id'),
  index('products', 'category_id'),
  index('products', 'supplier_id'),
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
    dialect: 'MYSQL',
    indexes: [],
  };
}

function mongoBuiltInSnapshot() {
  return {
    schemaId: 'builtin',
    label: 'QuerySense MongoDB demo (built-in)',
    catalog: 'querysense',
    builtIn: true,
    tables: MONGO_BUILT_IN_TABLES,
    foreignKeys: MONGO_BUILT_IN_REFERENCES,
    warnings: [],
    loadedAt: new Date(0).toISOString(),
    dialect: 'MONGODB',
    indexes: MONGO_BUILT_IN_INDEXES,
  };
}

const slotOf = (targetDb) => (targetDb === 'MONGODB' ? 'MONGODB' : 'SQL');

let mockActive = { SQL: builtInSnapshot(), MONGODB: mongoBuiltInSnapshot() };

export function mockActiveSchema(targetDb = 'SQL') {
  return mockActive[slotOf(targetDb)];
}

export function mockResetSchema(targetDb = 'SQL') {
  const slot = slotOf(targetDb);
  mockActive = { ...mockActive, [slot]: slot === 'MONGODB' ? mongoBuiltInSnapshot() : builtInSnapshot() };
  return mockActive[slot];
}

function sqlUpload({ ddl, label }) {
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
      tables.push({ name: match[1], columns, rowCount: 12000, embeddedArrays: [], fieldTypes: {} });
    }
    match = pattern.exec(ddl || '');
  }

  return {
    schemaId: `upload-${Math.random().toString(16).slice(2, 10)}`,
    label: label || 'Uploaded schema',
    catalog: 'querysense_uploads',
    builtIn: false,
    tables,
    foreignKeys: [],
    warnings: tables.length ? [] : ['No CREATE TABLE statements were found.'],
    loadedAt: new Date().toISOString(),
    dialect: 'MYSQL',
    indexes: [],
  };
}

function mongoUpload({ ddl, label }) {
  let collections = [];
  try {
    const parsed = JSON.parse(ddl || '');
    collections = Array.isArray(parsed) ? parsed : parsed.collections || [];
  } catch {
    collections = [];
  }

  const tables = collections.filter((c) => c && c.name).map((c) => {
    const properties = c.validator?.$jsonSchema?.properties || c.options?.validator?.$jsonSchema?.properties || {};
    const fieldTypes = { _id: 'objectId' };
    Object.entries(properties).forEach(([name, node]) => {
      if (name !== '_id') fieldTypes[name] = node?.bsonType || 'any';
    });
    return { name: c.name, columns: Object.keys(fieldTypes), rowCount: 12000, embeddedArrays: [], fieldTypes };
  });

  const indexes = collections.filter((c) => c && c.name).flatMap((c) => (c.indexes || []).map((i) => ({
    table: c.name,
    keys: Object.entries(i.key || {}).map(([column, direction]) => ({ column, direction })),
    unique: Boolean(i.unique),
  })));

  return {
    schemaId: `upload-${Math.random().toString(16).slice(2, 10)}`,
    label: label || 'Uploaded schema',
    catalog: 'querysense_uploads',
    builtIn: false,
    tables,
    foreignKeys: [],
    warnings: tables.length ? [] : ['No collections were found in the uploaded file.'],
    loadedAt: new Date().toISOString(),
    dialect: 'MONGODB',
    indexes,
  };
}

export function mockSchemaUpload({ ddl, label, targetDb = 'SQL' }) {
  const slot = slotOf(targetDb);
  const snapshot = slot === 'MONGODB' ? mongoUpload({ ddl, label }) : sqlUpload({ ddl, label });
  mockActive = { ...mockActive, [slot]: snapshot };
  return snapshot;
}
