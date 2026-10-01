# MongoDB Parity, Step 8 — Frontend — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The frontend treats MongoDB as a first-class database, everywhere step 4–7's backend already does:
- the Schema source page has a MySQL | MongoDB switch, each side with its own active schema, upload, paste box and reset;
- the shell's schema indicator follows the Workbench toggle;
- Analysis shows MongoDB's real plan with the same views as MySQL, in MongoDB's words;
- Optimization compares MongoDB rewrites;
- History shows the database;
- the shell says when MongoDB is down;
- the mock fixtures cover every call for MongoDB.

**Architecture:**
- **API layer:** functions take a `targetDb` and send the backend's `dialect`.
- **`ActiveSchemaProvider`:** moves inside `WorkbenchProvider` and holds one snapshot per database. Its `schema` is the one the Workbench targets, and `schemaFor(db)` reads either.
- **Analysis views:** the existing views pick their labels and glossary by `analysis.databaseType`, so MySQL rendering is unchanged.
- **Health:** the backend's `/api/health` gains `mongo: UP|DOWN|NOT_CONFIGURED`.

**Tech Stack:** React 19, Vite 8, Vitest 3 with Testing Library and jsdom, oxlint. Backend: Spring Boot 3.5, MongoDB sync driver.

**Spec:** `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md`: §9 (frontend), §11 (the `mongo` health field and the shell banner), §12 step 8.

## Global Constraints

- MySQL screens render exactly as they do today. Every MySQL label, note and glossary string stays.
- QuerySense recommends and never executes. The UI only shows statements and never runs them.
- The backend's MongoDB figures are measured (`estimate: false`). The UI says so, and never calls them estimates.
- Source comments: a one-line summary above a component or module at most. No other comments.
- Frontend tests: `cd frontend && npx vitest run <path>` for one file; `npm test` for all; `npm run lint` must stay clean; `npm run build` must succeed at the end.
- Backend tests: `cd backend && ./mvnw test`.
- Do not start the app (`npm run dev`, `spring-boot:run`). Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **Switching the Workbench toggle must not lose the other database's uploaded schema.** Each database keeps its own slot. Pinned in Task 3 (`appliedSnapshotLandsInItsOwnSlot`).
2. **A MongoDB `$group`, count or delete returns fewer documents than it reads without discarding any.** The "Wasted work" tile must not appear when the backend gave no discard figure (`filtered` is null). Pinned in Task 5 (`noWasteWithoutDiscardFigure`).
3. **An older backend whose `/api/health` has no `mongo` field must not trigger a MongoDB warning.** Pinned in Task 2 (`olderBackendHasNoMongoStatus`) and Task 8 (`silentWithoutMongoStatus`).
4. **An upload from the MongoDB tab must go to MongoDB.** Pinned in Task 4 (`pastedTextUploadsForSelectedDatabase`).
5. **MySQL's Analysis wording must not change** while the views gain MongoDB's. Pinned in Task 5 (`mysqlLabelsUnchanged`).

## Departures from the spec, on purpose

- **The schema switch defaults to the Workbench's current target** and is local to the page. The shell indicator follows the Workbench toggle, as the spec says.
- **Suggestions show their `suggestedChange`** (step 5's sub-project C) under the message. §9 doesn't list it, but the field is otherwise invisible.
- **History gains the Database column only.** `resourceLevel` is already shown, and `optimizedQuery` isn't added as a column.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../controller/HealthController.java`, `dto/HealthResponse.java` (modify) | `mongo` status |
| `frontend/src/api/contracts.js` (modify) | `targetDbOf`, `isMongoAnalysis` |
| `frontend/src/api/schemaUpload.js`, `comparison.js`, `health.js` (modify) | Dialect on schema calls; `userInput` on compare; health with `mongo` |
| `frontend/src/mock/fixtures.js` (modify) | MongoDB equivalents for every call |
| `frontend/src/context/ActiveSchemaContext.jsx`, `frontend/src/App.jsx` (modify) | One snapshot per database |
| `frontend/src/pages/SchemaSourcePage.jsx`, `components/schemaSource/*` (modify) | The switch and the MongoDB card |
| `frontend/src/lib/explainGlossary.js`, `components/analysis/{ExplainPlanView,PlanMetrics,PlanTreeView,SuggestionsList}.jsx`, `pages/AnalysisPage.jsx` (modify) | MongoDB plans in the same views |
| `frontend/src/components/analysis/MongoFindingsList.*` (delete) | Replaced |
| `frontend/src/pages/OptimizationPage.jsx`, `components/optimization/CostDeltaChart.jsx` (modify) | MongoDB compare |
| `frontend/src/components/history/HistoryTable.jsx` (modify) | Database column |
| `frontend/src/hooks/useBackendHealth.js`, `components/shell/{BackendHealthBanner,AppShell,EngineStatusBar}.jsx` (modify) | MongoDB status in the shell |

`backend/...` = `backend/src/main/java/com/querysense/backend`.

---

### Task 1: `/api/health` reports MongoDB

**Files:**
- Modify: `backend/.../dto/HealthResponse.java`, `backend/.../controller/HealthController.java`
- Modify test: `backend/src/test/java/com/querysense/backend/controller/HealthControllerTest.java`

**Interfaces:**
- Produces:
  - `HealthResponse` gains `String mongo` with a getter and setter, plus a 4-argument constructor `(status, schemaLabel, builtInSchema, mongo)`. The 3-argument constructor is kept (`mongo` null).
  - `HealthController(ActiveSchemaProvider, MongoClient, Environment)`. `mongo` is:
    - `NOT_CONFIGURED` when `spring.data.mongodb.uri` is blank;
    - otherwise `UP` when `{ping: 1}` on `admin` succeeds;
    - otherwise `DOWN`.
  - The endpoint itself always answers `status: UP`.

- [ ] **Step 1: Write the failing tests**

Replace `HealthControllerTest` with:

```java
package com.querysense.backend.controller;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.HealthResponse;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private ActiveSchemaProvider schema;
    private MongoClient mongo;
    private MongoDatabase admin;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        schema = mock(ActiveSchemaProvider.class);
        when(schema.active(Dialect.MYSQL)).thenReturn(KnownSchema.builtIn());
        mongo = mock(MongoClient.class);
        admin = mock(MongoDatabase.class);
        when(mongo.getDatabase("admin")).thenReturn(admin);
        when(admin.runCommand(any(Bson.class))).thenReturn(new Document("ok", 1.0));
        environment = new MockEnvironment().withProperty("spring.data.mongodb.uri", "mongodb://localhost:27017/querysense");
    }

    private HealthController controller() {
        return new HealthController(schema, mongo, environment);
    }

    @Test
    @DisplayName("the probe reports up, so a caller can branch on a field not a string")
    void reportsUp() {
        assertThat(controller().health().getStatus()).isEqualTo("UP");
    }

    @Test
    @DisplayName("the live schema is named, so a probe says which schema is answering")
    void namesActiveSchema() {
        HealthResponse response = controller().health();

        assertThat(response.getSchemaLabel()).isEqualTo(KnownSchema.builtIn().label());
        assertThat(response.isBuiltInSchema()).isTrue();
    }

    @Test
    @DisplayName("the legacy string endpoint still answers, so old probes do not break")
    void legacyStringEndpointSurvives() {
        assertThat(controller().test()).isEqualTo("QuerySense Running");
    }

    @Test
    @DisplayName("MongoDB answering a ping is UP")
    void mongoUp() {
        assertThat(controller().health().getMongo()).isEqualTo("UP");
    }

    @Test
    @DisplayName("MongoDB not answering is DOWN, and the backend itself still reports UP")
    void mongoDown() {
        when(admin.runCommand(any(Bson.class))).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        HealthResponse response = controller().health();

        assertThat(response.getMongo()).isEqualTo("DOWN");
        assertThat(response.getStatus()).isEqualTo("UP");
    }

    @Test
    @DisplayName("no MongoDB URI is NOT_CONFIGURED, and MongoDB is never contacted")
    void mongoNotConfigured() {
        environment.setProperty("spring.data.mongodb.uri", "");

        assertThat(controller().health().getMongo()).isEqualTo("NOT_CONFIGURED");
        verifyNoInteractions(mongo);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HealthControllerTest`
Expected: compilation failure (the 3-argument constructor and `getMongo` don't exist).

- [ ] **Step 3: Implement**

`HealthResponse`: add `private String mongo;`, the constructor

```java
    public HealthResponse(String status, String schemaLabel, boolean builtInSchema, String mongo) {
        this(status, schemaLabel, builtInSchema);
        this.mongo = mongo;
    }
```

and the accessors `public String getMongo() { return mongo; }` and `public void setMongo(String mongo) { this.mongo = mongo; }`.

`HealthController`, replacing the field, constructor and `health()`:

```java
    private final ActiveSchemaProvider activeSchema;
    private final MongoClient mongoClient;
    private final Environment environment;

    public HealthController(ActiveSchemaProvider activeSchema, MongoClient mongoClient, Environment environment) {
        this.activeSchema = activeSchema;
        this.mongoClient = mongoClient;
        this.environment = environment;
    }

    @GetMapping("/api/health")
    public HealthResponse health() {
        SchemaSnapshot active = activeSchema.active(Dialect.MYSQL);
        return new HealthResponse("UP", active.label(), active.builtIn(), mongoStatus());
    }

    private String mongoStatus() {
        String uri = environment.getProperty("spring.data.mongodb.uri");
        if (uri == null || uri.isBlank()) {
            return "NOT_CONFIGURED";
        }
        try {
            mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }
```

Add the imports `com.mongodb.client.MongoClient`, `org.bson.Document` and `org.springframework.core.env.Environment`.

- [ ] **Step 4: Run the whole backend suite**

Run: `cd backend && ./mvnw test`
Expected: all green, including the context load.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(health): /api/health reports MongoDB as UP, DOWN or NOT_CONFIGURED

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The API layer takes a database, and the mocks cover MongoDB

**Files:**
- Modify: `frontend/src/api/contracts.js`, `frontend/src/api/schemaUpload.js`, `frontend/src/api/comparison.js`, `frontend/src/api/health.js`, `frontend/src/mock/fixtures.js`
- Test: `frontend/src/api/schemaUpload.test.js` (new), `frontend/src/api/comparison.test.js` (add one case), `frontend/src/api/health.test.js` (rewritten), `frontend/src/mock/fixtures.test.js` (new)

**Interfaces:**
- Produces:
  - `contracts.js`: `targetDbOf(schema)` returns `TargetDb.MONGODB` when `schema.dialect === 'MONGODB'`, else `TargetDb.SQL`. `isMongoAnalysis(analysis)` is true when `databaseType` is MongoDB, case ignored.
  - `schemaUpload.js`:
    - helpers `dialectParam(targetDb)`, `toUploadForm(file, targetDb)` and `toUploadBody({ ddl, label }, targetDb)`;
    - `uploadSchemaFile(file, targetDb = SQL)`, `uploadSchemaText({ ddl, label }, targetDb = SQL)`, `getActiveSchema(targetDb = SQL)` and `resetSchema(targetDb = SQL)`, each sending `dialect`.
  - `comparison.js`: `toCompareRequest({ query, targetDb, userInput })` adds `userInput` when it is given.
  - `health.js`: `readHealth(data)` returns `{ status: 'up', mongo }`. `probeBackend(probe)` returns `{ status, mongo }`.
  - `fixtures.js`:
    - `mockActiveSchema(targetDb)`, `mockResetSchema(targetDb)` and `mockSchemaUpload({ ddl, label, targetDb })` keep one snapshot per database. The MongoDB demo has collections, typed paths, an embedded array, references and indexes.
    - `mockAnalyzeQuery` answers MongoDB with an explain-shaped analysis.
    - `mockCompare({ query, targetDb })` answers MongoDB, measured.

- [ ] **Step 1: Write the failing tests**

`schemaUpload.test.js`:

```js
import { describe, expect, it } from 'vitest';
import { dialectParam, toUploadBody, toUploadForm } from './schemaUpload';

describe('schema upload requests', () => {
  it('names the database for every call, MySQL by default', () => {
    expect(dialectParam('MONGODB')).toBe('MongoDB');
    expect(dialectParam('SQL')).toBe('MySQL');
    expect(dialectParam(undefined)).toBe('MySQL');
  });

  it('sends pasted text as ddl with its database', () => {
    expect(toUploadBody({ ddl: '{"collections": []}', label: 'Pasted schema' }, 'MONGODB')).toEqual({
      ddl: '{"collections": []}',
      label: 'Pasted schema',
      dialect: 'MongoDB',
    });
  });

  it('sends a file with a dialect part', () => {
    const form = toUploadForm(new File(['{}'], 'shop.json'), 'MONGODB');

    expect(form.get('dialect')).toBe('MongoDB');
    expect(form.get('file').name).toBe('shop.json');
  });
});
```

Add to `comparison.test.js`, inside its `describe`:

```js
  it('carries the question, so the rewrite lands on the right history row', () => {
    expect(toCompareRequest({ query: 'db.c.find({})', targetDb: 'MONGODB', userInput: 'all c' }))
      .toEqual({ query: 'db.c.find({})', databaseType: 'MongoDB', userInput: 'all c' });
  });
```

`health.test.js`, replacing the file:

```js
import { describe, expect, it } from 'vitest';
import { probeBackend } from './health';

describe('probeBackend', () => {
  it('reports up, with the MongoDB status the backend gave', async () => {
    const probe = async () => ({ data: { status: 'UP', mongo: 'DOWN' } });
    expect(await probeBackend(probe)).toEqual({ status: 'up', mongo: 'DOWN' });
  });

  it('reports up with no MongoDB status when an older backend gives none', async () => {
    const probe = async () => ({ data: 'Backend is running' });
    expect(await probeBackend(probe)).toEqual({ status: 'up', mongo: null });
  });

  it('reports down when the probe rejects', async () => {
    const probe = async () => {
      throw new Error('Network Error');
    };
    expect(await probeBackend(probe)).toEqual({ status: 'down', mongo: null });
  });

  it('reports down when the probe rejects with an HTTP status', async () => {
    const probe = async () => {
      const err = new Error('Request failed with status code 500');
      err.response = { status: 500 };
      throw err;
    };
    expect(await probeBackend(probe)).toEqual({ status: 'down', mongo: null });
  });
});
```

The test named in Review Focus 3, `olderBackendHasNoMongoStatus`, is the second case above. Name its `it(...)` string `'olderBackendHasNoMongoStatus: reports up with no MongoDB status when an older backend gives none'` so it can be found.

`fixtures.test.js`:

```js
import { describe, expect, it } from 'vitest';
import {
  mockActiveSchema, mockAnalyzeQuery, mockCompare, mockResetSchema, mockSchemaUpload,
} from './fixtures';

describe('MongoDB mock fixtures', () => {
  it('the MongoDB demo has collections, typed paths, an embedded array, references and indexes', () => {
    const schema = mockActiveSchema('MONGODB');
    const orders = schema.tables.find((t) => t.name === 'orders');

    expect(schema.dialect).toBe('MONGODB');
    expect(orders.embeddedArrays).toEqual(['items']);
    expect(orders.fieldTypes['items.quantity']).toBe('int');
    expect(schema.foreignKeys).toContainEqual(
      { fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: '_id' });
    expect(schema.indexes).toContainEqual(
      { table: 'customers', keys: [{ column: 'email', direction: 1 }], unique: true });
  });

  it('each database keeps its own active schema', () => {
    mockSchemaUpload({
      ddl: '{"collections": [{"name": "logs", "validator": {"$jsonSchema": {"properties": {"level": {"bsonType": "string"}}}}}]}',
      label: 'logs.json',
      targetDb: 'MONGODB',
    });

    expect(mockActiveSchema('MONGODB').label).toBe('logs.json');
    expect(mockActiveSchema('MONGODB').tables[0].fieldTypes).toEqual({ _id: 'objectId', level: 'string' });
    expect(mockActiveSchema('SQL').builtIn).toBe(true);
    expect(mockResetSchema('MONGODB').builtIn).toBe(true);
  });

  it('a MongoDB analysis is shaped like real explain output', () => {
    const { analysis, suggestions } = mockAnalyzeQuery({
      query: 'db.customers.find({ "city": "Pune" })', targetDb: 'MONGODB', resourceLevel: 'MEDIUM',
    });

    expect(analysis.databaseType).toBe('MongoDB');
    expect(analysis.accessType).toBe('COLLSCAN');
    expect(analysis.planTree.kind).toBe('COLLSCAN');
    expect(analysis.queryCost).toBeNull();
    expect(typeof analysis.executionMillis).toBe('number');
    expect(analysis.verdict.headline).toContain('collection');
    expect(suggestions[0].suggestedChange).toBe('db.customers.createIndex({ "city": 1 });');
  });

  it('a MongoDB comparison is measured, not estimated', () => {
    const result = mockCompare({
      query: 'db.customers.find({ "email": "a@b.c" }).sort({ "name": 1 })', targetDb: 'MONGODB',
    });

    expect(result.rewritten).toBe(true);
    expect(result.estimate).toBe(false);
    expect(result.optimizedQuery).not.toContain('.sort(');
    expect(result.summary).toContain('MongoDB examined');
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/api src/mock`
Expected:
- `schemaUpload.test.js` fails to import its helpers;
- the new comparison case fails on the missing `userInput`;
- `health.test.js` fails, because strings come back where objects are expected;
- `fixtures.test.js` fails, because the MongoDB data and shapes are missing.

- [ ] **Step 3: Implement**

`contracts.js`, appended:

```js
export function targetDbOf(schema) {
  return schema?.dialect === 'MONGODB' ? TargetDb.MONGODB : TargetDb.SQL;
}

export function isMongoAnalysis(analysis) {
  return String(analysis?.databaseType ?? '').toLowerCase() === 'mongodb';
}
```

`schemaUpload.js`, replacing the file:

```js
import { apiClient, USE_MOCK, mockDelay } from './client';
import { TARGET_DB_TO_BACKEND, TargetDb } from './contracts';
import { mockActiveSchema, mockResetSchema, mockSchemaUpload } from '../mock/fixtures';

export function dialectParam(targetDb) {
  return TARGET_DB_TO_BACKEND[targetDb] || TARGET_DB_TO_BACKEND[TargetDb.SQL];
}

export function toUploadForm(file, targetDb) {
  const form = new FormData();
  form.append('file', file);
  form.append('dialect', dialectParam(targetDb));
  return form;
}

export function toUploadBody({ ddl, label }, targetDb) {
  return { ddl, label, dialect: dialectParam(targetDb) };
}

export async function uploadSchemaFile(file, targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl: await file.text(), label: file.name, targetDb });
  }
  const { data } = await apiClient.post('/api/schema/upload', toUploadForm(file, targetDb), {
    headers: { 'Content-Type': undefined },
  });
  return data;
}

export async function uploadSchemaText({ ddl, label }, targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl, label: label || 'Pasted schema', targetDb });
  }
  const { data } = await apiClient.post('/api/schema/upload', toUploadBody({ ddl, label }, targetDb));
  return data;
}

export async function getActiveSchema(targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockActiveSchema(targetDb);
  }
  const { data } = await apiClient.get('/api/schema/active', { params: { dialect: dialectParam(targetDb) } });
  return data;
}

export async function resetSchema(targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockResetSchema(targetDb);
  }
  const { data } = await apiClient.post('/api/schema/reset', null, { params: { dialect: dialectParam(targetDb) } });
  return data;
}
```

`comparison.js`, `toCompareRequest` becomes:

```js
export function toCompareRequest({ query, targetDb, userInput }) {
  const request = {
    query,
    databaseType: TARGET_DB_TO_BACKEND[targetDb] || TARGET_DB_TO_BACKEND[TargetDb.SQL],
  };
  if (userInput) request.userInput = userInput;
  return request;
}
```

`health.js`, replacing the file:

```js
import { apiClient, USE_MOCK } from './client';

/** Liveness probe against GET /api/health, with MongoDB's status when the backend reports it. */
export function readHealth(data) {
  return { status: 'up', mongo: data?.mongo ?? null };
}

export async function probeBackend(probe) {
  try {
    const response = await probe();
    return readHealth(response?.data);
  } catch {
    return { status: 'down', mongo: null };
  }
}

export function checkBackendHealth() {
  if (USE_MOCK) return Promise.resolve({ status: 'up', mongo: 'UP' });
  return probeBackend(() => apiClient.get('/api/health', { timeout: 4000 }));
}
```

`fixtures.js` needs four changes:

1. Replace the `if (targetDb === 'MONGODB') { … }` branch at the top of `mockAnalyzeQuery` with:

```js
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
```

2. At the top of `mockCompare`, change the signature to `export function mockCompare({ query, targetDb }) {` and add:

```js
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
```

3. Replace everything from `const BUILT_IN_TABLES = [` to the end of the file with:

```js
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
```

4. In `mockAnalyzeQuery`'s SQL path, change `databaseType: targetDb,` to `databaseType: 'MySQL',` so it matches the backend.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/api src/mock`
Expected: PASS.

Run: `cd frontend && npm test && npm run lint`
Expected: all green; lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): schema, compare and health calls name their database; mocks cover MongoDB

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: One active schema per database

**Files:**
- Modify: `frontend/src/context/ActiveSchemaContext.jsx`, `frontend/src/App.jsx`
- Test: `frontend/src/context/ActiveSchemaContext.test.jsx` (new)

**Interfaces:**
- Consumes: `getActiveSchema(targetDb)`, `resetSchema(targetDb)`, `targetDbOf` (Task 2); `useWorkbench().targetDb`.
- Produces: `useActiveSchema()` returns `{ schema, schemas, schemaFor, loading, error, refresh, applySchema, reset }`.
  - `schema` is the snapshot of the Workbench's target.
  - `schemaFor(targetDb)` reads either database's snapshot.
  - `applySchema(snapshot)` stores it in the slot of `targetDbOf(snapshot)`.
  - `reset(targetDb = current)` resets that database only.
  - `refresh(targetDb = current)` reloads one database.

  In `App.jsx`, `WorkbenchProvider` now wraps `ActiveSchemaProvider`.

- [ ] **Step 1: Write the failing test**

```jsx
import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ getActiveSchema: vi.fn(), resetSchema: vi.fn() }));

import { getActiveSchema, resetSchema } from '../api';
import { ActiveSchemaProvider } from './ActiveSchemaContext';
import { useActiveSchema } from './activeSchema.js';
import { WorkbenchContext } from './workbench.js';

const SQL = { label: 'mysql demo', builtIn: true, dialect: 'MYSQL', tables: [] };
const MONGO = { label: 'mongo demo', builtIn: true, dialect: 'MONGODB', tables: [] };

let api;

function Probe() {
  api = useActiveSchema();
  return <span>{api.schema?.label ?? 'none'}</span>;
}

function renderFor(targetDb) {
  return render(
    <WorkbenchContext.Provider value={{ targetDb }}>
      <ActiveSchemaProvider>
        <Probe />
      </ActiveSchemaProvider>
    </WorkbenchContext.Provider>,
  );
}

describe('ActiveSchemaProvider', () => {
  beforeEach(() => {
    getActiveSchema.mockReset();
    resetSchema.mockReset();
    getActiveSchema.mockImplementation(async (db) => (db === 'MONGODB' ? MONGO : SQL));
  });

  it('loads both databases and shows the one the workbench targets', async () => {
    renderFor('MONGODB');

    expect(await screen.findByText('mongo demo')).toBeInTheDocument();
    expect(api.schemaFor('SQL')).toEqual(SQL);
    expect(getActiveSchema).toHaveBeenCalledWith('SQL');
    expect(getActiveSchema).toHaveBeenCalledWith('MONGODB');
  });

  it('appliedSnapshotLandsInItsOwnSlot: an uploaded MongoDB schema does not replace the MySQL one', async () => {
    renderFor('SQL');
    await screen.findByText('mysql demo');

    act(() => api.applySchema({ ...MONGO, label: 'shop.json', builtIn: false }));

    expect(api.schemaFor('MONGODB').label).toBe('shop.json');
    expect(screen.getByText('mysql demo')).toBeInTheDocument();
  });

  it('reset restores only the database it names', async () => {
    resetSchema.mockResolvedValue(MONGO);
    renderFor('SQL');
    await screen.findByText('mysql demo');
    act(() => api.applySchema({ ...MONGO, label: 'shop.json', builtIn: false }));

    await act(async () => {
      await api.reset('MONGODB');
    });

    expect(resetSchema).toHaveBeenCalledWith('MONGODB');
    expect(api.schemaFor('MONGODB')).toEqual(MONGO);
    expect(api.schemaFor('SQL')).toEqual(SQL);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/context/ActiveSchemaContext.test.jsx`
Expected: failures. Only one schema is loaded and `schemaFor` doesn't exist.

- [ ] **Step 3: Implement**

`ActiveSchemaContext.jsx`, replacing the file:

```jsx
import { useCallback, useEffect, useMemo, useState } from 'react';

import { getActiveSchema, resetSchema } from '../api';
import { TargetDb, targetDbOf } from '../api/contracts';
import { ActiveSchemaContext } from './activeSchema.js';
import { useWorkbench } from './workbench.js';

function messageOf(err) {
  return err?.response?.data?.message || err?.message || 'Could not read the active schema.';
}

export function ActiveSchemaProvider({ children }) {
  const { targetDb } = useWorkbench();
  const [schemas, setSchemas] = useState({ [TargetDb.SQL]: null, [TargetDb.MONGODB]: null });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;

    Promise.allSettled([getActiveSchema(TargetDb.SQL), getActiveSchema(TargetDb.MONGODB)]).then(([sql, mongo]) => {
      if (cancelled) return;
      setSchemas({
        [TargetDb.SQL]: sql.status === 'fulfilled' ? sql.value : null,
        [TargetDb.MONGODB]: mongo.status === 'fulfilled' ? mongo.value : null,
      });
      const failed = [sql, mongo].find((result) => result.status === 'rejected');
      if (failed) setError(messageOf(failed.reason));
      setLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, []);

  const applySchema = useCallback((snapshot) => {
    if (!snapshot) return;
    setSchemas((previous) => ({ ...previous, [targetDbOf(snapshot)]: snapshot }));
  }, []);

  const refresh = useCallback(async (db = targetDb) => {
    setLoading(true);
    setError(null);
    try {
      applySchema(await getActiveSchema(db));
    } catch (err) {
      setError(messageOf(err));
    } finally {
      setLoading(false);
    }
  }, [targetDb, applySchema]);

  const reset = useCallback(async (db = targetDb) => {
    const restored = await resetSchema(db);
    applySchema(restored);
    return restored;
  }, [targetDb, applySchema]);

  const schemaFor = useCallback((db) => schemas[db] ?? null, [schemas]);

  const value = useMemo(
    () => ({
      schema: schemas[targetDb] ?? null,
      schemas,
      schemaFor,
      loading,
      error,
      refresh,
      applySchema,
      reset,
    }),
    [schemas, targetDb, schemaFor, loading, error, refresh, applySchema, reset],
  );

  return <ActiveSchemaContext.Provider value={value}>{children}</ActiveSchemaContext.Provider>;
}
```

`App.jsx`: swap the two providers, so that `WorkbenchProvider` is outside and `ActiveSchemaProvider` inside it.

```jsx
    <EngineStatusProvider>
      <WorkbenchProvider>
        <ActiveSchemaProvider>
          <BrowserRouter>
            …unchanged routes…
          </BrowserRouter>
        </ActiveSchemaProvider>
      </WorkbenchProvider>
    </EngineStatusProvider>
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/context && npm test && npm run lint`
Expected: PASS, everything green, lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): one active schema per database, the workbench's target shown by default

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: The Schema source page's MySQL | MongoDB switch

**Files:**
- Modify: `frontend/src/pages/SchemaSourcePage.jsx`, `frontend/src/components/schemaSource/SchemaSourcePanel.jsx`, `SchemaSourcePanel.css`, `SchemaTablePreview.jsx`, `SchemaTablePreview.css`
- Test: `frontend/src/pages/SchemaSourcePage.test.jsx` (new), `frontend/src/components/schemaSource/SchemaTablePreview.test.jsx` (new)

**Interfaces:**
- Consumes: `useActiveSchema().schemaFor`, `applySchema`, `reset` (Task 3); `uploadSchemaFile(file, db)` and `uploadSchemaText(body, db)` (Task 2); `useWorkbench().targetDb`.
- Produces:
  - `SchemaSourcePanel({ dialect })`.
  - `SchemaTablePreview({ schema })`, which renders MongoDB's extras when `schema.dialect === 'MONGODB'`: typed paths, embedded arrays, references, indexes and document counts.
  - The page has a tab list labelled "Database" with the tabs "MySQL" and "MongoDB".

- [ ] **Step 1: Write the failing tests**

`SchemaTablePreview.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SchemaTablePreview } from './SchemaTablePreview';

const MONGO = {
  label: 'shop.json',
  builtIn: false,
  catalog: 'querysense_uploads',
  dialect: 'MONGODB',
  warnings: [],
  tables: [{
    name: 'orders',
    columns: ['_id', 'customer_id', 'items.quantity'],
    rowCount: 12000,
    embeddedArrays: ['items'],
    fieldTypes: { _id: 'objectId', customer_id: 'objectId', 'items.quantity': 'int' },
  }],
  foreignKeys: [{ fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: '_id' }],
  indexes: [{ table: 'orders', keys: [{ column: 'customer_id', direction: 1 }], unique: false }],
};

const MYSQL = {
  label: 'shop.sql',
  builtIn: false,
  catalog: 'querysense_uploads',
  dialect: 'MYSQL',
  warnings: [],
  tables: [{ name: 'customers', columns: ['id', 'name'], rowCount: 500, embeddedArrays: [], fieldTypes: { id: 'INT' } }],
  foreignKeys: [],
  indexes: [],
};

describe('SchemaTablePreview', () => {
  it('a MongoDB schema shows typed paths, embedded arrays, references, indexes and document counts', () => {
    render(<SchemaTablePreview schema={MONGO} />);

    expect(screen.getByText('1 collection · querysense_uploads')).toBeInTheDocument();
    expect(screen.getByText('items.quantity')).toBeInTheDocument();
    expect(screen.getByText('int')).toBeInTheDocument();
    expect(screen.getByText('Embedded array: items')).toBeInTheDocument();
    expect(screen.getByText('customer_id → customers._id')).toBeInTheDocument();
    expect(screen.getByText('index { customer_id: 1 }')).toBeInTheDocument();
    expect(screen.getByText('12,000 documents')).toBeInTheDocument();
  });

  it('a MySQL schema renders as before, with no type labels', () => {
    render(<SchemaTablePreview schema={MYSQL} />);

    expect(screen.getByText('1 table · querysense_uploads')).toBeInTheDocument();
    expect(screen.getByText('500 rows')).toBeInTheDocument();
    expect(screen.queryByText('INT')).toBeNull();
  });
});
```

`SchemaSourcePage.test.jsx`:

```jsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ uploadSchemaFile: vi.fn(), uploadSchemaText: vi.fn() }));

import { uploadSchemaText } from '../api';
import { SchemaSourcePage } from './SchemaSourcePage';
import { ActiveSchemaContext } from '../context/activeSchema.js';
import { WorkbenchContext } from '../context/workbench.js';

const SQL = {
  label: 'QuerySense demo schema (built-in)', builtIn: true, catalog: null, dialect: 'MYSQL', warnings: [],
  tables: [{ name: 'customers', columns: ['id', 'name'], rowCount: 0 }], foreignKeys: [], indexes: [],
};
const MONGO = {
  label: 'QuerySense MongoDB demo (built-in)', builtIn: true, catalog: 'querysense', dialect: 'MONGODB', warnings: [],
  tables: [{ name: 'orders', columns: ['_id'], rowCount: 0, embeddedArrays: [], fieldTypes: { _id: 'objectId' } }],
  foreignKeys: [], indexes: [],
};

function renderPage(targetDb) {
  const value = {
    schemaFor: (db) => (db === 'MONGODB' ? MONGO : SQL),
    applySchema: vi.fn(),
    reset: vi.fn(),
    loading: false,
    error: null,
  };
  render(
    <WorkbenchContext.Provider value={{ targetDb }}>
      <ActiveSchemaContext.Provider value={value}>
        <SchemaSourcePage />
      </ActiveSchemaContext.Provider>
    </WorkbenchContext.Provider>,
  );
  return value;
}

describe('SchemaSourcePage', () => {
  it('opens on the database the workbench targets', () => {
    renderPage('MONGODB');

    expect(screen.getByRole('tab', { name: 'MongoDB' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: 'Choose a .json file' })).toBeInTheDocument();
    expect(screen.getByText('QuerySense MongoDB demo (built-in)')).toBeInTheDocument();
  });

  it('switching to MongoDB shows its upload and its schema', () => {
    renderPage('SQL');
    expect(screen.getByRole('button', { name: 'Choose a .sql file' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'MongoDB' }));

    expect(screen.getByRole('button', { name: 'Choose a .json file' })).toBeInTheDocument();
    expect(screen.getByText('QuerySense MongoDB demo (built-in)')).toBeInTheDocument();
  });

  it('pastedTextUploadsForSelectedDatabase: a pasted document goes to MongoDB from the MongoDB tab', async () => {
    uploadSchemaText.mockResolvedValue(MONGO);
    const context = renderPage('MONGODB');

    fireEvent.change(screen.getByLabelText(/paste a collections document/i), {
      target: { value: '{"collections": []}' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Use this schema' }));

    await waitFor(() => expect(uploadSchemaText)
      .toHaveBeenCalledWith({ ddl: '{"collections": []}', label: 'Pasted schema' }, 'MONGODB'));
    await waitFor(() => expect(context.applySchema).toHaveBeenCalledWith(MONGO));
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/pages/SchemaSourcePage.test.jsx src/components/schemaSource`
Expected: failures. There is no switch, no `.json` button and no MongoDB rendering yet.

- [ ] **Step 3: Implement**

`SchemaSourcePage.jsx`, replacing the file:

```jsx
import { useState } from 'react';
import { PageHeader } from '../components/common/PageHeader';
import { SchemaSourcePanel } from '../components/schemaSource/SchemaSourcePanel';
import { SchemaTablePreview } from '../components/schemaSource/SchemaTablePreview';
import { TargetDb } from '../api/contracts';
import { useActiveSchema } from '../context/activeSchema.js';
import { useWorkbench } from '../context/workbench.js';

const DATABASES = [
  { value: TargetDb.SQL, label: 'MySQL' },
  { value: TargetDb.MONGODB, label: 'MongoDB' },
];

export function SchemaSourcePage() {
  const { targetDb } = useWorkbench();
  const [dialect, setDialect] = useState(targetDb);
  const { schemaFor, loading, error } = useActiveSchema();

  return (
    <div>
      <PageHeader
        title="Schema source"
        description="Upload your own schema for MySQL or MongoDB and QuerySense works against it — the rule engine, the LLM prompt and the plan analysis all follow it. Each database keeps its own active schema, and the built-in demos stay where they are."
      />

      <div className="schema-switch" role="tablist" aria-label="Database">
        {DATABASES.map((db) => (
          <button
            key={db.value}
            type="button"
            role="tab"
            aria-selected={dialect === db.value}
            className={`schema-switch__option${dialect === db.value ? ' schema-switch__option--active' : ''}`}
            onClick={() => setDialect(db.value)}
          >
            {db.label}
          </button>
        ))}
      </div>

      <SchemaSourcePanel key={dialect} dialect={dialect} />

      {loading && <p className="page-note">Reading the active schema…</p>}
      {error && (
        <p className="page-note" role="alert">
          {error}
        </p>
      )}

      <SchemaTablePreview schema={schemaFor(dialect)} />
    </div>
  );
}
```

`SchemaSourcePanel.jsx`, replacing the file:

```jsx
import { useRef, useState } from 'react';
import { Button } from '../common/Button';
import { uploadSchemaFile, uploadSchemaText } from '../../api';
import { TargetDb } from '../../api/contracts';
import { useActiveSchema } from '../../context/activeSchema.js';
import './SchemaSourcePanel.css';

const SQL_PLACEHOLDER = `CREATE TABLE clients (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80),
  city VARCHAR(60)
);`;

const MONGO_PLACEHOLDER = `{"collections": [{
  "name": "clients",
  "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name"], "properties": {
    "name": {"bsonType": "string"},
    "city": {"bsonType": "string"}}}},
  "indexes": [{"key": {"city": 1}}]
}]}`;

export function SchemaSourcePanel({ dialect = TargetDb.SQL }) {
  const isMongo = dialect === TargetDb.MONGODB;
  const { schemaFor, applySchema, reset } = useActiveSchema();
  const schema = schemaFor(dialect);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const fileInput = useRef(null);

  async function run(action) {
    setBusy(true);
    setError(null);
    try {
      applySchema(await action());
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'The schema could not be uploaded.');
    } finally {
      setBusy(false);
    }
  }

  const replacesAnUpload = Boolean(schema) && !schema.builtIn;
  const warning = isMongo
    ? replacesAnUpload
      ? `Uploading replaces “${schema.label}” — that upload's collections are dropped. The built-in MongoDB demo is never touched.`
      : 'Uploaded collections are created in a separate MongoDB database, with your $jsonSchema validators and indexes, and filled with generated documents. The built-in MongoDB demo is left exactly as it is, and you can return to it at any time.'
    : replacesAnUpload
      ? `Uploading replaces “${schema.label}” — that upload's tables are dropped. The built-in demo schema is never touched.`
      : 'Uploaded tables are created in a separate database. The built-in demo schema is left exactly as it is, and you can return to it at any time.';
  const textId = `schema-text-${dialect}`;

  return (
    <div className="schema-source">
      <p className="schema-source__warning">{warning}</p>

      <div className="schema-source__actions">
        <input
          ref={fileInput}
          type="file"
          accept={isMongo ? '.json,application/json' : '.sql,text/plain'}
          className="schema-source__file"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) run(() => uploadSchemaFile(file, dialect));
            event.target.value = '';
          }}
        />
        <Button onClick={() => fileInput.current?.click()} loading={busy}>
          {isMongo ? 'Choose a .json file' : 'Choose a .sql file'}
        </Button>
        {replacesAnUpload && (
          <Button variant="ghost" onClick={() => run(() => reset(dialect))} loading={busy}>
            Back to the built-in schema
          </Button>
        )}
      </div>

      <label className="schema-source__label" htmlFor={textId}>
        {isMongo ? '…or paste a collections document (JSON)' : '…or paste CREATE TABLE statements'}
      </label>
      <textarea
        id={textId}
        className="schema-source__textarea"
        rows={10}
        spellCheck={false}
        value={text}
        placeholder={isMongo ? MONGO_PLACEHOLDER : SQL_PLACEHOLDER}
        onChange={(event) => setText(event.target.value)}
      />

      <Button
        variant="secondary"
        onClick={() => run(() => uploadSchemaText({ ddl: text, label: 'Pasted schema' }, dialect))}
        loading={busy}
        disabled={!text.trim()}
      >
        Use this schema
      </Button>

      {error && (
        <p className="schema-source__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
```

Append to `SchemaSourcePanel.css`:

```css
.schema-switch {
  display: inline-flex;
  gap: var(--space-1);
  padding: var(--space-1);
  margin-bottom: var(--space-5);
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.schema-switch__option {
  border: 0;
  background: transparent;
  color: var(--ink-secondary);
  font: inherit;
  font-size: var(--text-sm);
  padding: var(--space-1) var(--space-4);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.schema-switch__option--active {
  background: var(--bg-elevated);
  color: var(--ink);
}
```

`SchemaTablePreview.jsx`, replacing the file:

```jsx
import './SchemaTablePreview.css';

const SQL_NOTE = 'These tables were created from your schema and filled with generated rows, so that EXPLAIN has real statistics to plan against. The access paths and index choices it reports are real; the data itself is synthetic.';
const MONGO_NOTE = 'These collections were created from your schema, with its validators and indexes, and filled with generated documents, so that explain has real data to plan against. The plan stages and index choices it reports are real; the data itself is synthetic.';

function indexText(index) {
  const keys = index.keys.map((key) => `${key.column}: ${key.direction}`).join(', ');
  return `index { ${keys} }${index.unique ? ' · unique' : ''}`;
}

export function SchemaTablePreview({ schema }) {
  if (!schema || !schema.tables.length) return null;

  const isMongo = schema.dialect === 'MONGODB';
  const [one, many] = isMongo ? ['collection', 'collections'] : ['table', 'tables'];
  const rowUnit = isMongo ? 'documents' : 'rows';
  const foreignKeysFrom = (table) => schema.foreignKeys.filter((fk) => fk.fromTable === table);
  const indexesOn = (table) => (schema.indexes || []).filter((index) => index.table === table);

  return (
    <section className="schema-preview">
      <header className="schema-preview__head">
        <h2 className="schema-preview__title">{schema.label}</h2>
        <span className="schema-preview__meta">
          {schema.tables.length} {schema.tables.length === 1 ? one : many}
          {schema.catalog ? ` · ${schema.catalog}` : ' · connected database'}
        </span>
      </header>

      {!schema.builtIn && <p className="schema-preview__note">{isMongo ? MONGO_NOTE : SQL_NOTE}</p>}

      {schema.warnings.length > 0 && (
        <ul className="schema-preview__warnings">
          {schema.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}

      <ul className="schema-preview__list">
        {schema.tables.map((table) => (
          <li key={table.name} className="schema-preview__table">
            <div className="schema-preview__table-head">
              <code className="schema-preview__table-name">{table.name}</code>
              {table.rowCount > 0 && (
                <span className="schema-preview__rows">{table.rowCount.toLocaleString()} {rowUnit}</span>
              )}
            </div>

            <div className="schema-preview__columns">
              {table.columns.map((column) => (
                <span key={column} className="schema-preview__column">
                  {isMongo ? (
                    <>
                      <span className="schema-preview__path">{column}</span>
                      {table.fieldTypes?.[column] && (
                        <span className="schema-preview__type">{table.fieldTypes[column]}</span>
                      )}
                    </>
                  ) : (
                    column
                  )}
                </span>
              ))}
            </div>

            {isMongo && table.embeddedArrays?.length > 0 && (
              <div className="schema-preview__arrays">
                {`Embedded array${table.embeddedArrays.length === 1 ? '' : 's'}: ${table.embeddedArrays.join(', ')}`}
              </div>
            )}

            {foreignKeysFrom(table.name).map((fk) => (
              <div key={`${fk.fromColumn}-${fk.toTable}-${fk.toColumn}`} className="schema-preview__fk">
                {fk.fromColumn} → {fk.toTable}.{fk.toColumn}
              </div>
            ))}

            {isMongo && indexesOn(table.name).map((index) => (
              <div key={indexText(index)} className="schema-preview__index">
                {indexText(index)}
              </div>
            ))}
          </li>
        ))}
      </ul>
    </section>
  );
}
```

Append to `SchemaTablePreview.css`:

```css
.schema-preview__type {
  margin-left: var(--space-2);
  color: var(--ink-muted);
}

.schema-preview__arrays,
.schema-preview__index {
  margin-top: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--ink-muted);
}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/pages src/components/schemaSource && npm test && npm run lint`
Expected: PASS, everything green, lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): the Schema source page switches between MySQL and MongoDB, each with its own upload and card

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Analysis shows MongoDB's real plan

**Files:**
- Modify: `frontend/src/lib/explainGlossary.js`, `frontend/src/components/analysis/ExplainPlanView.jsx`, `PlanMetrics.jsx`, `PlanTreeView.jsx`, `SuggestionsList.jsx`, `SuggestionsList.css`, `frontend/src/pages/AnalysisPage.jsx`
- Delete: `frontend/src/components/analysis/MongoFindingsList.jsx`, `MongoFindingsList.css`, `MongoFindingsList.test.jsx`
- Test: `frontend/src/components/analysis/ExplainPlanView.test.jsx` (new), `PlanMetrics.test.jsx` (new), `SuggestionsList.test.jsx` (new), `PlanTreeView.test.jsx` (add one case)

**Interfaces:**
- Consumes: `isMongoAnalysis` (Task 2), `useActiveSchema().schemaFor` (Task 3).
- Produces:
  - `explainGlossary.js` adds `MONGO_ACCESS_TYPE_GLOSS`, `mongoAccessTypeGloss(stage)`, `MONGO_FIELD_GLOSS` and `MONGO_PLAN_FOOTNOTE`.
  - `PlanTreeView({ tree, dialect })`, where `dialect` is `'MONGODB'` or anything else for MySQL.
  - `SuggestionsList` shows `suggestedChange` in a `<code>`.
  - The Analysis page uses the same views for both databases.

- [ ] **Step 1: Write the failing tests**

`ExplainPlanView.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ExplainPlanView } from './ExplainPlanView';
import { ActiveSchemaContext } from '../../context/activeSchema.js';

const MONGO_UPLOAD = { builtIn: false, catalog: 'querysense_uploads', dialect: 'MONGODB' };

function renderPlan(plan, schema = null) {
  render(
    <ActiveSchemaContext.Provider value={{ schemaFor: () => schema }}>
      <ExplainPlanView plan={plan} />
    </ActiveSchemaContext.Provider>,
  );
}

const MONGO_PLAN = {
  databaseType: 'MongoDB',
  explainFailed: false,
  estimatedRows: 12000,
  estimatedRowsReturned: 1040,
  filtered: 8.7,
  executionMillis: 9,
  tables: [{
    tableName: 'customers', accessType: 'COLLSCAN', keyUsed: null, possibleKeys: null,
    estimatedRows: 12000, estimatedRowsReturned: 1040, fullTableScan: true, usesIndex: false,
    usesFilesort: false, usesTemporaryTable: false,
  }],
};

describe('ExplainPlanView', () => {
  it('a MongoDB plan speaks of collections, documents and plan stages', () => {
    renderPlan(MONGO_PLAN, MONGO_UPLOAD);

    expect(screen.getAllByText('Plan stage').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Documents examined').length).toBeGreaterThan(0);
    expect(screen.getByText('Collection scan', { selector: '.explain-plan__table-badge' })).toBeInTheDocument();
    expect(screen.getByText(/This plan is MongoDB's/)).toBeInTheDocument();
    expect(screen.queryByText('Joined on')).toBeNull();
    expect(screen.queryByText(/MySQL/)).toBeNull();
  });

  it('mysqlLabelsUnchanged: a MySQL plan keeps its labels', () => {
    renderPlan({
      databaseType: 'MySQL',
      explainFailed: false,
      tables: [{ tableName: 'customers', accessType: 'ALL', estimatedRows: 300, fullTableScan: true }],
    });

    expect(screen.getAllByText('Access type').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Rows examined').length).toBeGreaterThan(0);
    expect(screen.getByText('Joined on')).toBeInTheDocument();
    expect(screen.getByText('Full scan')).toBeInTheDocument();
  });

  it('a failed MongoDB explain says explain, not EXPLAIN', () => {
    renderPlan({ databaseType: 'MongoDB', explainFailed: true, tables: [] });

    expect(screen.getAllByText('Unavailable — explain failed').length).toBeGreaterThan(0);
  });
});
```

`PlanMetrics.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PlanMetrics } from './PlanMetrics';

describe('PlanMetrics', () => {
  it('MongoDB figures are documents, measured, with the execution time and no cost tile', () => {
    render(<PlanMetrics plan={{
      databaseType: 'MongoDB', estimatedRows: 12000, estimatedRowsReturned: 1040, filtered: 8.7, executionMillis: 9,
    }} />);

    expect(screen.getByText('Documents examined')).toBeInTheDocument();
    expect(screen.getByText('9 ms')).toBeInTheDocument();
    expect(screen.queryByText('Query cost')).toBeNull();
    expect(screen.getByText(/measured, not estimated/)).toBeInTheDocument();
  });

  it('noWasteWithoutDiscardFigure: a MongoDB group or count shows no wasted-work tile', () => {
    render(<PlanMetrics plan={{
      databaseType: 'MongoDB', estimatedRows: 2000, estimatedRowsReturned: 369, filtered: null, executionMillis: 24,
    }} />);

    expect(screen.queryByText('Wasted work')).toBeNull();
  });

  it('MySQL keeps rows, the cost tile and the estimate footnote', () => {
    render(<PlanMetrics plan={{
      databaseType: 'MySQL', estimatedRows: 300, estimatedRowsReturned: 30, queryCost: 31.45,
    }} />);

    expect(screen.getByText('Rows examined')).toBeInTheDocument();
    expect(screen.getByText('Query cost')).toBeInTheDocument();
    expect(screen.getByText(/MySQL's own estimate/)).toBeInTheDocument();
  });
});
```

`SuggestionsList.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SuggestionsList } from './SuggestionsList';

describe('SuggestionsList', () => {
  it('shows the statement a suggestion carries as its fix', () => {
    render(<SuggestionsList suggestions={[{
      message: 'Collection scan on customers.', severity: 'CRITICAL', source: 'DOMAIN',
      suggestedChange: 'db.customers.createIndex({ "city": 1 });',
    }]} />);

    expect(screen.getByText('db.customers.createIndex({ "city": 1 });')).toBeInTheDocument();
  });
});
```

Add to `PlanTreeView.test.jsx`, inside its `describe`. The file already imports `render` and `screen`; if it doesn't import `screen`, add it.

```jsx
  it('a MongoDB plan tree names stages and counts documents, with no MySQL wording', () => {
    render(<PlanTreeView dialect="MONGODB" tree={{
      kind: 'SORT', label: 'customers', usesFilesort: true, children: [
        { kind: 'COLLSCAN', label: 'customers', accessType: 'COLLSCAN', estimatedRows: 2000, children: [] },
      ],
    }} />);

    expect(screen.getByText('SORT')).toBeInTheDocument();
    expect(screen.getByText('COLLSCAN')).toBeInTheDocument();
    expect(screen.getByText('2,000 documents')).toBeInTheDocument();
    expect(screen.getByText('In-memory sort')).toBeInTheDocument();
    expect(screen.queryByText('Using filesort')).toBeNull();
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/components/analysis`
Expected: the new MongoDB cases fail (MySQL wording is rendered), and `SuggestionsList` has no statement. `mysqlLabelsUnchanged` and the MySQL `PlanMetrics` case pass already.

- [ ] **Step 3: Implement**

`explainGlossary.js`, appended:

```js
export const MONGO_ACCESS_TYPE_GLOSS = {
  COLLSCAN: 'Reads every document in the collection, one at a time.',
  IXSCAN: 'Walks an index to find the matching documents.',
  EXPRESS_IXSCAN: "Finds a single document through a unique index, on MongoDB's fast path.",
  EXPRESS_IDHACK: "Finds a single document by its _id, on MongoDB's fast path.",
  IDHACK: 'Finds a single document by its _id.',
  FETCH: 'Reads the documents an index scan pointed to.',
  EQ_LOOKUP: 'Joins in documents from another collection ($lookup).',
  COUNT_SCAN: 'Counts index entries without reading the documents.',
};

export function mongoAccessTypeGloss(stage) {
  return stage ? MONGO_ACCESS_TYPE_GLOSS[stage] ?? null : null;
}

export const MONGO_FIELD_GLOSS = {
  keyUsed: 'The index MongoDB actually used.',
  possibleKeys:
    'Indexes that a plan MongoDB tried and rejected would have used. If one is listed but none was chosen, MongoDB found that reading the collection directly did less work.',
  estimatedRows: 'How many documents MongoDB examined, counted while running the plan with explain.',
  estimatedRowsReturned: 'How many of those documents matched and were returned.',
  fullTableScan: 'Whether MongoDB read every document in the collection instead of using an index.',
  usesFilesort:
    'Documents had to be sorted in memory after being read, because no index supplied them in the order this query asked for.',
  usesTemporaryTable:
    'A grouping stage needed more memory than MongoDB allows one stage, so part of the work was written to disk and read back.',
  executionMillis:
    'How long MongoDB took to run the plan under explain. Nothing was written, and no documents were sent back to QuerySense.',
};

export const MONGO_PLAN_FOOTNOTE =
  "These figures are MongoDB's own, counted while running the plan with explain at executionStats — measured, not estimated. MongoDB reports no cost figure for a plan, so none is shown. Nothing is written: a delete is planned, not run.";
```

`ExplainPlanView.jsx`, replacing the file:

```jsx
import { motion } from 'framer-motion';
import { PlanMetrics } from './PlanMetrics';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import { useActiveSchema } from '../../context/activeSchema.js';
import { isMongoAnalysis, TargetDb } from '../../api/contracts';
import {
  accessTypeGloss, FIELD_GLOSS, mongoAccessTypeGloss, MONGO_FIELD_GLOSS,
} from '../../lib/explainGlossary';
import './ExplainPlanView.css';

function flagRows(isMongo, gloss, scanKey) {
  return [
    {
      key: scanKey,
      label: isMongo ? 'Collection scan' : 'Full table scan',
      bool: true,
      badTone: true,
      gloss: (v) => (v ? gloss.fullTableScan : null),
    },
    {
      key: 'usesFilesort',
      label: isMongo ? 'In-memory sort' : 'Uses filesort',
      bool: true,
      badTone: true,
      gloss: (v) => (v ? gloss.usesFilesort : null),
    },
    {
      key: 'usesTemporaryTable',
      label: isMongo ? 'Grouping spilled to disk' : 'Temporary table',
      bool: true,
      badTone: true,
      gloss: (v) => (v ? gloss.usesTemporaryTable : null),
    },
  ];
}

function summaryRows(isMongo) {
  const gloss = isMongo ? MONGO_FIELD_GLOSS : FIELD_GLOSS;
  return [
    { key: 'accessType', label: isMongo ? 'Plan stage' : 'Access type', gloss: isMongo ? mongoAccessTypeGloss : accessTypeGloss },
    { key: 'keyUsed', label: 'Index used', gloss: () => gloss.keyUsed },
    ...flagRows(isMongo, gloss, 'hasFullTableScan'),
  ];
}

function tableRows(isMongo) {
  const gloss = isMongo ? MONGO_FIELD_GLOSS : FIELD_GLOSS;
  const unit = isMongo ? 'Documents' : 'Rows';
  return [
    { key: 'accessType', label: isMongo ? 'Plan stage' : 'Access type', gloss: isMongo ? mongoAccessTypeGloss : accessTypeGloss },
    { key: 'keyUsed', label: 'Index used', gloss: () => gloss.keyUsed },
    { key: 'possibleKeys', label: 'Indexes considered', gloss: () => gloss.possibleKeys },
    { key: 'estimatedRows', label: `${unit} examined`, number: true, gloss: () => gloss.estimatedRows },
    { key: 'estimatedRowsReturned', label: `${unit} returned`, number: true, gloss: () => gloss.estimatedRowsReturned },
    ...(isMongo ? [] : [{ key: 'ref', label: 'Joined on', gloss: () => gloss.ref }]),
    ...flagRows(isMongo, gloss, 'fullTableScan'),
  ];
}

function formatValue(value, row) {
  if (row.bool) return value ? 'Yes' : 'No';
  if (row.number && typeof value === 'number') return value.toLocaleString();
  return value ?? '—';
}

function valueClass(row, value, unavailable) {
  if (unavailable) return 'explain-plan__row-value explain-plan__row-value--unavailable';
  if (!row.bool) return 'explain-plan__row-value';
  return `explain-plan__row-value explain-plan__row-value--${value ? 'danger' : 'good'}`;
}

function MetricRows({ rows, source, unavailable, unavailableText, reduced }) {
  return (
    <ul className="explain-plan__rows">
      {rows.map((row, i) => {
        const value = source[row.key];
        const gloss = !unavailable && row.gloss ? row.gloss(value) : null;
        return (
          <motion.li
            key={row.key}
            className="explain-plan__row"
            initial={reduced ? false : { opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.2, delay: reduced ? 0 : i * 0.05, ease: [0.23, 1, 0.32, 1] }}
          >
            <div className="explain-plan__row-main">
              <span className="explain-plan__row-label">{row.label}</span>
              <span className={valueClass(row, value, unavailable)}>
                {unavailable ? unavailableText : formatValue(value, row)}
              </span>
            </div>
            {gloss && <p className="explain-plan__row-gloss">{gloss}</p>}
          </motion.li>
        );
      })}
    </ul>
  );
}

export function ExplainPlanView({ plan }) {
  const reduced = useReducedMotion();
  const isMongo = isMongoAnalysis(plan);
  const { schemaFor } = useActiveSchema();
  const schema = schemaFor(isMongo ? TargetDb.MONGODB : TargetDb.SQL);
  const generated = Boolean(schema) && !schema.builtIn;
  const unavailable = Boolean(plan.explainFailed);
  const unavailableText = isMongo ? 'Unavailable — explain failed' : 'Unavailable — EXPLAIN failed';
  const tables = Array.isArray(plan.tables) ? plan.tables : [];
  const perTable = !unavailable && tables.length > 0;

  return (
    <div className="explain-plan">
      {generated && !unavailable && (
        <p className="explain-plan__provenance">
          {isMongo ? (
            <>
              This plan is MongoDB&apos;s, run against <code>{schema.catalog}</code>. Those collections were
              created from your uploaded schema and filled with generated documents — the plan stages and
              index choices are real, the document counts describe synthetic data.
            </>
          ) : (
            <>
              This plan is MySQL&apos;s, run against <code>{schema.catalog}</code>. Those tables were
              created from your uploaded schema and filled with generated rows — the access paths and
              index choices are real, the row counts describe synthetic data.
            </>
          )}
        </p>
      )}

      <PlanMetrics plan={plan} />

      {perTable ? (
        <div className="explain-plan__tables">
          {tables.length > 1 && (
            <p className="explain-plan__tables-intro">
              {isMongo
                ? `MongoDB reads ${tables.length} collections for this query. Each is reported separately here.`
                : `MySQL reads ${tables.length} tables for this query. Each is planned separately, so each is reported separately here.`}
            </p>
          )}
          {tables.map((table, tableIndex) => (
            <section
              className="explain-plan__table"
              key={`${table.tableName ?? 'table'}-${tableIndex}`}
            >
              <header className="explain-plan__table-header">
                <span className="explain-plan__table-name">{table.tableName ?? '—'}</span>
                <span
                  className={`explain-plan__table-badge explain-plan__table-badge--${
                    table.fullTableScan ? 'danger' : 'good'
                  }`}
                >
                  {table.fullTableScan
                    ? isMongo ? 'Collection scan' : 'Full scan'
                    : `Index: ${table.keyUsed || 'yes'}`}
                </span>
              </header>
              {table.extra && <p className="explain-plan__table-extra">{table.extra}</p>}
              <MetricRows
                rows={tableRows(isMongo)}
                source={table}
                unavailable={false}
                unavailableText={unavailableText}
                reduced={reduced}
              />
            </section>
          ))}
        </div>
      ) : (
        <MetricRows
          rows={summaryRows(isMongo)}
          source={plan}
          unavailable={unavailable}
          unavailableText={unavailableText}
          reduced={reduced}
        />
      )}
    </div>
  );
}
```

Before replacing the MySQL provenance and tables-intro sentences, read today's file and copy them character for character into the `else` branches above. If they differ from the text shown here, today's text wins; record a ruling.

`PlanMetrics.jsx`: add the imports `MONGO_FIELD_GLOSS` and `MONGO_PLAN_FOOTNOTE` (from `explainGlossary`) and `isMongoAnalysis` (from `../../api/contracts`). Replace `PlanMetrics` with:

```jsx
export function PlanMetrics({ plan }) {
  if (plan.explainFailed) return null;

  const isMongo = isMongoAnalysis(plan);
  const gloss = isMongo ? MONGO_FIELD_GLOSS : FIELD_GLOSS;
  const unit = isMongo ? 'Documents' : 'Rows';
  const examined = plan.estimatedRows;
  const returned = plan.estimatedRowsReturned;
  const waste = isMongo && plan.filtered == null ? null : amplification(examined, returned);

  const tiles = [
    { key: 'examined', label: `${unit} examined`, value: count(examined), gloss: gloss.estimatedRows },
    { key: 'returned', label: `${unit} returned`, value: count(returned), gloss: gloss.estimatedRowsReturned },
    {
      key: 'waste',
      label: 'Wasted work',
      value: waste,
      tone: waste && waste !== 'none' ? 'danger' : undefined,
      gloss:
        waste === 'none'
          ? isMongo
            ? 'Nearly every document read is a document returned — almost nothing is discarded.'
            : 'Nearly every row read is a row returned — almost nothing is discarded.'
          : isMongo
            ? 'Documents read for each document returned. The rest are read and thrown away.'
            : 'Rows read for each row returned. The rest are read and thrown away.',
    },
    isMongo
      ? {
          key: 'time',
          label: 'Execution time',
          value: typeof plan.executionMillis === 'number' ? `${plan.executionMillis.toLocaleString()} ms` : null,
          gloss: MONGO_FIELD_GLOSS.executionMillis,
        }
      : {
          key: 'cost',
          label: 'Query cost',
          value: typeof plan.queryCost === 'number' ? plan.queryCost.toLocaleString() : null,
          gloss: QUERY_COST_CAVEAT,
        },
  ].filter((tile) => tile.value !== null && tile.value !== undefined);

  if (tiles.length === 0) return null;

  return (
    <section className="plan-metrics" aria-label="Plan metrics">
      <div className="plan-metrics__grid">
        {tiles.map((tile) => (
          <Metric key={tile.key} label={tile.label} value={tile.value} gloss={tile.gloss} tone={tile.tone} />
        ))}
      </div>
      <p className="plan-metrics__footnote">
        {isMongo
          ? MONGO_PLAN_FOOTNOTE
          : "Every figure here is MySQL's own estimate, read from its execution plan. QuerySense does not run your query, so none of these is a measured result."}
      </p>
    </section>
  );
}
```

`PlanTreeView.jsx`, replacing the file:

```jsx
import './PlanTreeView.css';

function PlanNode({ node, isMongo }) {
  const children = node.children || [];
  const isTable = isMongo || node.kind === 'table';
  const flags = isMongo
    ? [node.usesTemporaryTable && 'Spilled to disk', node.usesFilesort && 'In-memory sort'].filter(Boolean)
    : [node.usesTemporaryTable && 'Using temporary', node.usesFilesort && 'Using filesort'].filter(Boolean);

  return (
    <li className={`plan-tree__node plan-tree__node--${isTable ? 'table' : 'op'}`}>
      <div className="plan-tree__row">
        <span className="plan-tree__label">{isMongo ? node.kind : node.label || node.kind}</span>

        {isMongo && node.label && node.label !== node.keyUsed && node.label !== node.kind && (
          <span className="plan-tree__chip" title="The collection this stage reads">
            {node.label}
          </span>
        )}
        {!isMongo && isTable && node.accessType && (
          <span className="plan-tree__chip" title="How MySQL reaches rows in this table">
            {node.accessType}
          </span>
        )}
        {isTable && node.keyUsed && (
          <span className="plan-tree__chip plan-tree__chip--key" title="Index used">
            {node.keyUsed}
          </span>
        )}
        {node.estimatedRows != null && (
          <span className="plan-tree__rows">
            {node.estimatedRows.toLocaleString()} {isMongo ? 'documents' : 'rows'}
          </span>
        )}
        {node.cost != null && (
          <span className="plan-tree__cost" title="MySQL's own cost estimate — unitless">
            cost {node.cost.toLocaleString()}
          </span>
        )}
        {flags.map((flag) => (
          <span key={flag} className="plan-tree__chip plan-tree__chip--warn">{flag}</span>
        ))}
      </div>

      {children.length > 0 && (
        <ul className="plan-tree__children">
          {children.map((child, i) => (
            <PlanNode key={`${child.kind}-${child.label}-${i}`} node={child} isMongo={isMongo} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** The execution plan as the tree it actually is: MySQL's operations, or MongoDB's stages. */
export function PlanTreeView({ tree, dialect }) {
  const isMongo = dialect === 'MONGODB';

  if (!tree) {
    return (
      <p className="plan-tree__empty">
        {isMongo ? (
          'No plan tree for this query: MongoDB returned no winning plan to draw.'
        ) : (
          <>
            No plan tree for this query. It comes from a side <code>EXPLAIN FORMAT=JSON</code> probe;
            when that probe cannot run, the per-table plan above is still complete.
          </>
        )}
      </p>
    );
  }

  return (
    <ul className="plan-tree">
      <PlanNode node={tree} isMongo={isMongo} />
    </ul>
  );
}
```

`SuggestionsList.jsx`: after `<p className="suggestions-list__message">{s.message}</p>`, add:

```jsx
            {s.suggestedChange && <code className="suggestions-list__change">{s.suggestedChange}</code>}
```

and append to `SuggestionsList.css`:

```css
.suggestions-list__change {
  display: block;
  margin-top: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--text-xs);
  color: var(--ink-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
```

`AnalysisPage.jsx`:
- Remove the `MongoFindingsList` import and the `TargetDb` import.
- Add `import { isMongoAnalysis } from '../api/contracts';`.
- Change both `PageHeader` descriptions to: `"Runs the real plan — MySQL EXPLAIN or MongoDB explain — and the optimization and resource-aware suggestions those findings trigger."`
- Replace the whole `{(build.targetDb || targetDb) === TargetDb.MONGODB ? ( … ) : ( … )}` expression with:

```jsx
          <ExplainPlanView plan={analysis} />

          <section>
            <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>
              Plan tree
            </h3>
            <p className="page-note" style={{ marginBottom: 'var(--space-3)' }}>
              {isMongoAnalysis(analysis)
                ? 'The same plan as above, as MongoDB executes it: each stage feeds the one that wraps it, and a sort or a $lookup appears as its own stage.'
                : 'The same plan as above, in the shape MySQL actually executes it: operations wrap the tables they drive, and a sort is attributed to the step that performs it.'}
            </p>
            <PlanTreeView tree={analysis.planTree} dialect={isMongoAnalysis(analysis) ? 'MONGODB' : 'SQL'} />
          </section>
```

`targetDb` is still used by `runAnalysis`, so keep it in the destructuring.

Delete `MongoFindingsList.jsx`, `MongoFindingsList.css` and `MongoFindingsList.test.jsx`.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/components/analysis && npm test && npm run lint`
Expected: PASS, everything green, lint clean, and no remaining import of `MongoFindingsList`. Confirm the last with `grep -rn MongoFindingsList src`, which prints nothing.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): Analysis shows MongoDB's real plan in its own words; the text-check view is retired

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Optimization compares MongoDB rewrites

**Files:**
- Modify: `frontend/src/pages/OptimizationPage.jsx`, `frontend/src/components/optimization/CostDeltaChart.jsx`
- Test: `frontend/src/pages/OptimizationPage.test.jsx` (new)

**Interfaces:**
- Consumes: `compareQueries({ query, targetDb, userInput })` (Task 2).
- Produces:
  - `CostDeltaChart({ before, after, caption })`, whose default caption is today's text.
  - The Optimization page compares for both databases, sends the Workbench question as `userInput`, and words MongoDB's figures as measured.

- [ ] **Step 1: Write the failing test**

```jsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ adviseSchema: vi.fn(), compareQueries: vi.fn() }));

import { compareQueries } from '../api';
import { OptimizationPage } from './OptimizationPage';
import { WorkbenchContext } from '../context/workbench.js';
import { ActiveSchemaContext } from '../context/activeSchema.js';

const QUERY = 'db.customers.find({ "email": "a@b.c" }).sort({ "name": 1 })';

function renderPage() {
  render(
    <MemoryRouter>
      <WorkbenchContext.Provider value={{
        build: { generatedQuery: QUERY, targetDb: 'MONGODB', naturalLanguageQuery: 'customer a@b.c by name' },
        analysis: { databaseType: 'MongoDB' },
        targetDb: 'MONGODB',
        resourceLevel: 'MEDIUM',
        schemaAdvice: [],
        setSchemaAdvice: vi.fn(),
      }}>
        <ActiveSchemaContext.Provider value={{ schema: { builtIn: true, dialect: 'MONGODB', catalog: 'querysense' } }}>
          <OptimizationPage />
        </ActiveSchemaContext.Provider>
      </WorkbenchContext.Provider>
    </MemoryRouter>,
  );
}

describe('OptimizationPage', () => {
  it('compares a MongoDB query, sends the question, and calls the figures measured', async () => {
    compareQueries.mockResolvedValue({
      originalQuery: QUERY,
      optimizedQuery: 'db.customers.find({ "email": "a@b.c" })',
      rewritten: true,
      changes: [{ ruleId: 'RW3', explanation: 'Dropped .sort().' }],
      originalAnalysis: { estimatedRows: 3 },
      optimizedAnalysis: { estimatedRows: 1 },
      estimate: false,
      summary: '1 rewrite rule applied. MongoDB examined 2 fewer documents under the rewrite, measured by running both plans with explain.',
    });
    renderPage();

    expect(screen.queryByText('SQL only, for now')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Compare rewrite' }));

    await waitFor(() => expect(compareQueries).toHaveBeenCalledWith({
      query: QUERY, targetDb: 'MONGODB', userInput: 'customer a@b.c by name',
    }));
    expect(await screen.findByText('Documents examined')).toBeInTheDocument();
    expect(screen.getByText(/MongoDB examined 2 fewer documents/)).toBeInTheDocument();
    expect(screen.getByText(/measured by MongoDB/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/pages/OptimizationPage.test.jsx`
Expected: failure. The page shows "SQL only, for now" and no Compare button for MongoDB.

- [ ] **Step 3: Implement**

`CostDeltaChart.jsx`: change the signature to

```jsx
export function CostDeltaChart({
  before,
  after,
  caption = 'Estimated rows examined, from EXPLAIN. This is an estimate — not a measured runtime.',
}) {
```

and render `{caption}` inside `<p className="cost-delta__caption">`.

`OptimizationPage.jsx`:

1. In `runComparison`, call:

```jsx
      const result = await compareQueries({
        query: build.generatedQuery,
        targetDb: activeDb,
        userInput: build.naturalLanguageQuery,
      });
```

2. The uploaded-schema note under "Recommended schema changes" becomes:

```jsx
        {schema && !schema.builtIn && (
          <p className="page-note">
            {isMongo
              ? 'Anchored to MongoDB explain over your uploaded collections, which hold generated documents.'
              : 'Anchored to EXPLAIN over your uploaded schema, whose tables hold generated rows.'}
          </p>
        )}
```

3. In the "No schema changes recommended" `EmptyState`, change both conditions `schema?.builtIn` to `schema?.builtIn && !isMongo`. The demo-tables sentence describes the MySQL demo only.

4. Replace the rewrite `<section>`'s header button condition `{!isMongo && !comparison && (` with `{!comparison && (`.

5. Replace the rewrite note paragraph with:

```jsx
        <p className="page-note">
          {isMongo
            ? "Every rule here keeps the documents the query returns. The figures come from MongoDB's explain over both versions — counted while running each plan, so they are measured, not estimated."
            : "Every rule here preserves the rows the query returns. The row and cost figures come from EXPLAIN over both versions — they are the optimiser's estimates, not measurements."}
        </p>
```

6. Replace the conditional that starts `{isMongo ? (` with `<EmptyState title="SQL only, for now" … />` with the same chain without the MongoDB branch:

```jsx
        {compareError ? (
          <EmptyState title="Comparison failed" detail={compareError} />
        ) : !comparison && !loading ? (
          <EmptyState
            title="Not compared yet"
            detail={isMongo
              ? 'Click Compare rewrite to run the rewriter and explain both versions in MongoDB.'
              : 'Click Compare rewrite to run the rewriter and EXPLAIN both versions.'}
          />
        ) : comparison && !comparison.rewritten ? (
```

Everything from `<EmptyState title="No rewrite applied" … />` onwards stays.

7. In the comparison result, the rows heading and chart become:

```jsx
                <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                  {isMongo ? 'Documents examined' : 'Estimated rows read'}
                </h4>
                {comparison.originalAnalysis?.estimatedRows != null
                  && comparison.optimizedAnalysis?.estimatedRows != null ? (
                  <CostDeltaChart
                    before={comparison.originalAnalysis.estimatedRows}
                    after={comparison.optimizedAnalysis.estimatedRows}
                    caption={isMongo
                      ? 'Documents examined, counted by MongoDB while running each plan with explain — measured, not estimated.'
                      : undefined}
                  />
                ) : (
                  <p className="page-note">
                    {isMongo
                      ? 'MongoDB reported no documents-examined figure for one of the two plans.'
                      : 'EXPLAIN reported no row estimate for one of the two plans.'}
                  </p>
                )}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/pages && npm test && npm run lint`
Expected: PASS, everything green, lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): Optimization compares MongoDB rewrites, measured, and records them against the question

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: History shows the database

**Files:**
- Modify: `frontend/src/components/history/HistoryTable.jsx`
- Test: add to `frontend/src/components/history/HistoryTable.test.jsx`

**Interfaces:**
- Produces: a "Database" column after "Generated query", showing `record.databaseType`, or `—` when absent.

- [ ] **Step 1: Write the failing tests**

Add inside the `describe` of `HistoryTable.test.jsx`:

```jsx
  it('shows which database the query was written for', () => {
    expect(within(rowFor({ ...RECORD, databaseType: 'MongoDB' })).getByText('MongoDB')).toBeInTheDocument();
  });

  it('has a Database column', () => {
    render(<HistoryTable records={[RECORD]} />);
    expect(screen.getByRole('columnheader', { name: 'Database' })).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/components/history`
Expected: the two new tests fail.

- [ ] **Step 3: Implement**

In `HistoryTable.jsx`, add `<th>Database</th>` after `<th>Generated query</th>`. Add `<td className="history-table__meta">{r.databaseType || EMPTY}</td>` after the generated-query cell.

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/components/history && npm run lint`
Expected: PASS, every existing case included.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): History shows the database each query was written for

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: The shell knows about MongoDB

**Files:**
- Modify: `frontend/src/hooks/useBackendHealth.js`, `frontend/src/components/shell/BackendHealthBanner.jsx`, `BackendHealthBanner.css`, `AppShell.jsx`, `EngineStatusBar.jsx`
- Test: add to `frontend/src/hooks/useBackendHealth.test.jsx` and `frontend/src/components/shell/BackendHealthBanner.test.jsx`. New: `frontend/src/components/shell/EngineStatusBar.test.jsx`.

**Interfaces:**
- Consumes: `checkBackendHealth()` returning `{ status, mongo }` (Task 2); `useActiveSchema().schema` following the Workbench (Task 3).
- Produces:
  - `useBackendHealth()` returns `{ status, mongo, refresh }`. A `check` that returns a plain string still works (`mongo: null`).
  - `BackendHealthBanner({ status, mongo, baseUrl, onRetry })` shows a MongoDB notice when the backend is up and `mongo` is `DOWN` or `NOT_CONFIGURED`.
  - `EngineStatusBar` names the database and says collections for MongoDB.

- [ ] **Step 1: Write the failing tests**

Add to `useBackendHealth.test.jsx`:

```jsx
  it('reports MongoDB status alongside the backend status', async () => {
    const { result } = renderHook(() => useBackendHealth({ check: async () => ({ status: 'up', mongo: 'DOWN' }) }));

    await waitFor(() => expect(result.current.mongo).toBe('DOWN'));
    expect(result.current.status).toBe('up');
  });
```

Add to `BackendHealthBanner.test.jsx`:

```jsx
  it('says MongoDB is down while the backend is up, and that MySQL keeps working', () => {
    render(<BackendHealthBanner status="up" mongo="DOWN" />);
    expect(screen.getByRole('status')).toHaveTextContent(/MongoDB is not answering/);
    expect(screen.getByRole('status')).toHaveTextContent(/MySQL keeps working/);
  });

  it('says MongoDB is not configured', () => {
    render(<BackendHealthBanner status="up" mongo="NOT_CONFIGURED" />);
    expect(screen.getByRole('status')).toHaveTextContent(/not configured/);
  });

  it('silentWithoutMongoStatus: stays out of the way when MongoDB is up or the backend gave no status', () => {
    const { container, rerender } = render(<BackendHealthBanner status="up" mongo="UP" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<BackendHealthBanner status="up" mongo={null} />);
    expect(container).toBeEmptyDOMElement();
  });
```

`EngineStatusBar.test.jsx`:

```jsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EngineStatusBar } from './EngineStatusBar';
import { ActiveSchemaContext } from '../../context/activeSchema.js';
import { EngineStatusContext } from '../../context/engineStatus.js';

function renderWith(schema) {
  render(
    <EngineStatusContext.Provider value={{ status: null }}>
      <ActiveSchemaContext.Provider value={{ schema }}>
        <EngineStatusBar />
      </ActiveSchemaContext.Provider>
    </EngineStatusContext.Provider>,
  );
}

describe('EngineStatusBar', () => {
  it('names the built-in MongoDB demo when the workbench targets MongoDB', () => {
    renderWith({ builtIn: true, dialect: 'MONGODB', label: 'QuerySense MongoDB demo (built-in)', tables: [] });
    expect(screen.getByText('Built-in MongoDB demo')).toBeInTheDocument();
  });

  it('counts collections for an uploaded MongoDB schema', () => {
    renderWith({ builtIn: false, dialect: 'MONGODB', label: 'shop.json', catalog: 'querysense_uploads', tables: [{}, {}, {}] });
    expect(screen.getByText('shop.json · 3 collections · generated data')).toBeInTheDocument();
  });

  it('keeps the MySQL wording', () => {
    renderWith({ builtIn: true, dialect: 'MYSQL', label: 'QuerySense demo schema (built-in)', tables: [] });
    expect(screen.getByText('Built-in demo schema')).toBeInTheDocument();
  });
});
```

If `engineStatus.js` names its context export differently, use that name and record a ruling. Check with `cat src/context/engineStatus.js`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/hooks src/components/shell`
Expected: the MongoDB cases fail. The existing cases keep passing.

- [ ] **Step 3: Implement**

`useBackendHealth.js`, replacing the file:

```js
import { useCallback, useEffect, useRef, useState } from 'react';
import { checkBackendHealth } from '../api/health';
import { USE_MOCK } from '../api/client';

/** Polls the backend liveness probe so a dead API, or a dead MongoDB, reads as one banner. */
export function useBackendHealth({
  check = checkBackendHealth,
  useMock = USE_MOCK,
  intervalMs = 20000,
} = {}) {
  const [status, setStatus] = useState(useMock ? 'mock' : 'unknown');
  const [mongo, setMongo] = useState(null);
  const alive = useRef(true);

  const refresh = useCallback(async () => {
    if (useMock) return;
    const next = await check();
    const health = typeof next === 'string' ? { status: next, mongo: null } : next;
    if (alive.current) {
      setStatus(health.status);
      setMongo(health.mongo ?? null);
    }
  }, [check, useMock]);

  useEffect(() => {
    alive.current = true;
    if (useMock) return undefined;
    refresh();
    const id = setInterval(refresh, intervalMs);
    return () => {
      alive.current = false;
      clearInterval(id);
    };
  }, [refresh, useMock, intervalMs]);

  return { status, mongo, refresh };
}
```

`BackendHealthBanner.jsx`: change the signature to `({ status, mongo, baseUrl, onRetry })`. After the `status === 'mock'` block, add:

```jsx
  if (status === 'up' && (mongo === 'DOWN' || mongo === 'NOT_CONFIGURED')) {
    return (
      <div className="backend-health backend-health--mongo" role="status">
        <span className="backend-health__dot" aria-hidden="true" />
        <span>
          {mongo === 'DOWN'
            ? 'MongoDB is not answering. MySQL keeps working; MongoDB analysis, comparison and uploads return an error until mongod is running again.'
            : 'MongoDB is not configured — spring.data.mongodb.uri is empty — so MongoDB targets cannot be analysed or uploaded. MySQL keeps working.'}
        </span>
        {onRetry ? (
          <button type="button" className="backend-health__retry" onClick={onRetry}>
            Retry
          </button>
        ) : null}
      </div>
    );
  }
```

Append to `BackendHealthBanner.css`. Read the file first and model the rule on the existing `.backend-health--down` block, using `var(--warning)` for its colour token:

```css
.backend-health--mongo {
  border-color: var(--warning);
  color: var(--ink);
}

.backend-health--mongo .backend-health__dot {
  background: var(--warning);
}
```

`AppShell.jsx`: destructure `const { status, mongo, refresh } = useBackendHealth();` and pass `mongo={mongo}` to `BackendHealthBanner`.

`EngineStatusBar.jsx`: replace the `title` and the label expression of the schema indicator:

```jsx
        title={
          uploaded
            ? schema.dialect === 'MONGODB'
              ? `Uploaded MongoDB schema, provisioned into ${schema.catalog}. Its collections hold generated documents.`
              : `Uploaded schema, provisioned into ${schema.catalog}. Its tables hold generated rows.`
            : schema?.dialect === 'MONGODB'
              ? 'The built-in MongoDB demo, in the querysense database.'
              : 'The built-in demo schema, in the connected database.'
        }
```

```jsx
          {!schema
            ? 'Schema unknown'
            : schema.builtIn
              ? schema.dialect === 'MONGODB' ? 'Built-in MongoDB demo' : 'Built-in demo schema'
              : `${schema.label} · ${schema.tables.length} ${schema.dialect === 'MONGODB' ? 'collections' : 'tables'} · generated data`}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && npx vitest run src/hooks src/components/shell && npm test && npm run lint`
Expected: PASS, everything green, lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): the shell says when MongoDB is down or unconfigured, and its schema indicator names the database

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Build, and record it in `PROJECT.md`

**Files:**
- Modify: `PROJECT.md`

- [ ] **Step 1: Full checks**

Run: `cd frontend && npm test && npm run lint && npm run build`
Expected: every test passes, lint is clean, and the build succeeds.

Run: `cd backend && ./mvnw test`
Expected: all green.

- [ ] **Step 2: `PROJECT.md`**
  - **New session entry:** add `### Session of <execution date> — MongoDB parity, step 8 (frontend)` after the step 7 session. It contains:
    - the plan path;
    - a table of Tasks 1–8 with commits;
    - the three departures above;
    - the frontend and backend test counts;
    - the note that no screen was opened in a browser.
  - **"Next:" line:** in the steps 1–3 entry, it becomes: steps 1–8 are complete; step 9 (docs: `PROJECT.md` §5 records the decision and removes the two Mongo non-goal sections; `RULES.md` renumbered) is next.
  - **Live checklist** (backend on MySQL with `mongod` running, `npm run dev`):
    1. Schema source: the MySQL | MongoDB switch opens on the Workbench's target. The MongoDB side shows the demo's eight collections with typed paths, `items` as an embedded array, references and indexes.
    2. Upload `backend/src/main/resources/mongo/demo-collections.json` on the MongoDB side. The card shows 12,000 documents per collection, and the MySQL side still shows its own schema.
    3. Toggle the Workbench to MongoDB. The shell indicator names the MongoDB schema.
    4. Workbench (MongoDB): build `customers in mumbai`, then open Analysis. It shows the verdict, plan stage `COLLSCAN`, "Documents examined", execution time, no cost tile, and the plan tree. There is no "text checks" note.
    5. Optimization (MongoDB): the advisor shows `db.customers.createIndex({ "city": 1 });`. Compare rewrite runs and says the figures are measured.
    6. History shows a Database column.
    7. Stop `mongod`. Within 20 seconds the shell shows "MongoDB is not answering", and MySQL screens keep working.

- [ ] **Step 3: Commit**

```bash
git add PROJECT.md
git commit -m "docs: record MongoDB parity step 8 — the frontend's MongoDB side

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
