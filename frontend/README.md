# QuerySense frontend

React (Vite) frontend for QuerySense — a hybrid rule-based/LLM-fallback
natural-language query builder, analyzer, and advisory system. Talks to the
Spring Boot backend in `../backend`.

Stack: React 19, Vite 8, react-router-dom 7, framer-motion, axios, oxlint.

## Running it

```bash
npm install
npm run dev          # → http://localhost:5173
```

The backend must be running on `:8080` for anything but mock mode. Confirm it
with `curl http://localhost:8080/test` → `QuerySense Running`.

Requests go **straight** to `http://localhost:8080` — there is no Vite proxy,
because the backend's `AppConfig.corsConfigurer` allows all origins. Point
`VITE_API_BASE_URL` somewhere else to hit a different backend.

## Configuration (`.env`)

| Variable | Default | Meaning |
|---|---|---|
| `VITE_USE_MOCK` | `false` | Run entirely on `src/mock/fixtures.js`, no backend needed. See the warning below. |
| `VITE_API_BASE_URL` | `http://localhost:8080` | Backend origin. |
| `VITE_API_TIMEOUT` | `65000` | Axios timeout in ms. |

**`VITE_USE_MOCK` fails toward mock, not toward real.** `client.js` reads
`import.meta.env.VITE_USE_MOCK !== 'false'`, so *any* value that isn't the
literal string `false` — including unset — turns mock mode **on**. If the app
shows plausible data while the backend is down, check this first.

**Don't lower `VITE_API_TIMEOUT` below 65 s.** The backend's own
`http.client.read-timeout-ms` is 60 s, because the LLM fallback runs a local
`qwen2.5-coder:7b` through Ollama and a cold first token can take tens of
seconds. A shorter client timeout aborts requests the backend is still
answering, surfacing a network error for a call that was about to succeed.

## Backend endpoints

Four are real and verified against the running backend; one has no backend at
all. `ENDPOINT_STATUS` in `contracts.js` records this in code.

| Function | Endpoint | Status |
|---|---|---|
| `buildQuery` | `POST /api/query/generate` | real |
| `analyzeQuery` | `POST /api/query/analyze` | real |
| `adviseSchema` | `POST /api/query/schema-advisor` | real |
| `getHistory` | `GET /api/query/history` | real |
| `compareQueries` | — | **always simulated** |

`compareQueries` ignores `VITE_USE_MOCK` and always returns generated data,
because no comparison endpoint exists to fall back to. Swap it for a real
`apiClient.post` once one does.

Two things the API deliberately does **not** have:

- **No `POST /history`.** The backend writes a history row itself as a side
  effect of `/generate`. `postHistory` is a no-op outside mock mode.
- **No separate domain/resource endpoints.** Both modules fold into
  `/analyze`'s response as a flat `suggestions` array, each entry tagged
  `source: "DOMAIN" | "RESOURCE"`. There is no rewritten query anywhere in the
  real API.

## Which engine answered

`handledBy` (and `source` on history rows) is one of `RULE_ENGINE`, `CACHE`, or
`OLLAMA`. It drives the badge, the confidence meter's tint, and the header
status pill.

`GEMINI` is a **legacy value, still live on read.** The backend renamed its LLM
tier from `GEMINI` to `OLLAMA` when it moved to a local model, and deliberately
did not rewrite existing rows — so `/history` still serves both. Use
`isLlmSource(handledBy)` from `contracts.js` rather than comparing against a
literal; it covers both values, and a future provider change is one edit there
instead of one per component.

## Structure

```
src/
  api/          one file per backend endpoint, contracts.js is the shared shape
  mock/         mock data generators used when VITE_USE_MOCK is not "false"
  components/   shell/, common/, and one folder per feature area
  pages/        one page per route
  context/      cross-page state (engine status, the workbench's last query)
  lib/          hand-rolled query syntax highlighter + line diff
  styles/       design tokens (tokens.css) and global reset (base.css)
```

Routes: Workbench (`/`), Analysis, Optimization, Schema advisor, History, About.

`contracts.js` is the only file that knows a backend field name — it maps
`targetDb: 'SQL'` → `databaseType: "MySQL"`, `domain` → `domainProfile`, and so
on. Components only ever see the frontend's own vocabulary.

## Design system

Colors are the same key used on the team's architecture diagrams — teal
for the rule-based engine, amber for the LLM fallback, blue for the
frontend/API layer, purple for data access, red for errors — defined as
CSS variables in `src/styles/tokens.css`. Typography is IBM Plex Sans
(UI/headings) and IBM Plex Mono (queries, identifiers, metrics only).

## Checks

```bash
npm run lint     # oxlint
npm run build    # vite build
```
