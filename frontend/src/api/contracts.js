/**
 * Central contract definitions for every QuerySense backend endpoint.
 *
 * These shapes were confirmed by running the actual merged backend
 * (querysense-backend @ master) and hitting every endpoint live — not
 * guessed from the synopsis. Four endpoints are REAL: build, analyze,
 * schema-advisor, history. The Comparison Engine has no endpoint at all
 * yet (Vedant's last task, blocked on the others) — comparison.js stays
 * fully simulated until it exists.
 *
 * Two things the real API does NOT have, that an earlier draft of this
 * file assumed:
 * - No POST /history — the backend saves history automatically as a
 *   side effect of /generate. The frontend never writes history directly.
 * - No separate domain/resource-optimization endpoints — Eeshaan's two
 *   modules are folded into /analyze's response as a flat `suggestions`
 *   array (each tagged `source: "DOMAIN"|"RESOURCE"`), not a rewritten
 *   query. There is no "optimizedQuery" text anywhere in the real API —
 *   that only exists once the Comparison Engine is built.
 */

export const Domain = {
  ECOMMERCE: 'ECOMMERCE',
  ANALYTICAL: 'ANALYTICAL',
  TRANSACTIONAL: 'TRANSACTIONAL',
  OTHER: 'OTHER',
};

// The real backend's DomainProfile enum has no OTHER value. When OTHER is
// selected we send TRANSACTIONAL — DomainOptimizationService's own
// documented behavior for a null/unrecognized profile is to fall back to
// TRANSACTIONAL as "the strictest, safest default", so this mirrors a
// choice the backend already makes itself rather than inventing one.
export const DOMAIN_TO_BACKEND_PROFILE = {
  ECOMMERCE: 'ECOMMERCE',
  ANALYTICAL: 'ANALYTICAL',
  TRANSACTIONAL: 'TRANSACTIONAL',
  OTHER: 'TRANSACTIONAL',
};

export const ResourceLevel = {
  LOW: 'LOW',
  MEDIUM: 'MEDIUM',
  HIGH: 'HIGH',
};

export const TargetDb = {
  SQL: 'SQL',
  MONGODB: 'MONGODB',
};

// The backend's databaseType strings are "MySQL" / "MongoDB", not our
// internal SQL/MONGODB enum values — mapped at the API-call boundary so
// components only ever deal with TargetDb.
export const TARGET_DB_TO_BACKEND = {
  SQL: 'MySQL',
  MONGODB: 'MongoDB',
};

// The LLM tier is reported as "OLLAMA" — the backend switched provider from
// Gemini to a local Ollama model and deliberately renamed the value rather
// than reusing "GEMINI", so old history rows stay distinguishable from new
// ones (see QueryGenerationServiceImpl.SOURCE_LLM). GEMINI is therefore not
// dead: /generate never returns it any more, but /history still serves rows
// written before the rename, so anything rendering a history `source` must
// keep handling it.
export const HandledBy = {
  RULE_ENGINE: 'RULE_ENGINE',
  CACHE: 'CACHE',
  OLLAMA: 'OLLAMA',
  GEMINI: 'GEMINI', // legacy — pre-rename history rows only
};

/**
 * True when a source value means "the LLM fallback handled this", covering
 * both the current OLLAMA value and legacy GEMINI rows. Use this instead of
 * comparing against a single literal, so a future provider change is one
 * edit here rather than one per component.
 * @param {string} [handledBy]
 */
export function isLlmSource(handledBy) {
  return handledBy === HandledBy.OLLAMA || handledBy === HandledBy.GEMINI;
}

export const SuggestionSeverity = {
  INFO: 'INFO',
  WARNING: 'WARNING',
  CRITICAL: 'CRITICAL',
};

/**
 * @typedef {Object} QueryBuildRequest
 * @property {string} query
 * @property {'MySQL'|'MongoDB'} databaseType
 */

/**
 * @typedef {Object} QueryBuildResponse
 * @property {string} generatedQuery
 * @property {string} targetDb        echoes the request's databaseType
 * @property {'RULE_ENGINE'|'CACHE'|'OLLAMA'} handledBy
 * @property {number} confidence      fixed per-source heuristic, not a computed score — see backend QueryResponse.java
 */

/**
 * @typedef {Object} AnalyzeRequest
 * @property {string} query
 * @property {'MySQL'|'MongoDB'} databaseType
 * @property {'ECOMMERCE'|'ANALYTICAL'|'TRANSACTIONAL'} domainProfile
 * @property {'LOW'|'MEDIUM'|'HIGH'} resourceLevel
 */

/**
 * @typedef {Object} QueryAnalysisResult
 * @property {string} tableName
 * @property {string} queryType
 * @property {string} databaseType
 * @property {number} [estimatedRows]      MySQL only
 * @property {string} [accessType]         MySQL only — ALL, ref, index, range, const...
 * @property {boolean} usesIndex
 * @property {boolean} hasFullTableScan
 * @property {boolean} usesFilesort
 * @property {boolean} usesTemporaryTable
 * @property {string} [keyUsed]            MySQL only
 * @property {boolean} hasUnindexedFilter  MongoDB only
 * @property {boolean} hasUnanchoredRegex  MongoDB only
 * @property {string} [filteredColumn]
 * @property {string} [orderByColumn]      MySQL only
 * @property {string[]} warnings           complete sentences, safe to render directly
 * @property {boolean} explainFailed       MySQL only — true when EXPLAIN could not run (see warnings for why); distinct from a Mongo result, where the EXPLAIN-derived fields are simply not applicable
 */

/**
 * @typedef {Object} OptimizationSuggestion
 * @property {string} message   complete sentence, safe to render directly
 * @property {'INFO'|'WARNING'|'CRITICAL'} severity
 * @property {'DOMAIN'|'RESOURCE'} source
 */

/**
 * @typedef {Object} AnalyzeResponse
 * @property {QueryAnalysisResult} analysis
 * @property {OptimizationSuggestion[]} suggestions
 */

/**
 * @typedef {Object} SchemaRecommendation
 * @property {'INDEX'|'NORMALIZATION'|'STRUCTURAL'} type
 * @property {string} [targetTable]
 * @property {string} [targetColumn]
 * @property {string} reason            references the specific analysis signal that fired it
 * @property {string} suggestedAction   e.g. a literal CREATE INDEX statement
 */

/**
 * @typedef {Object} HistoryRecord
 * @property {number} id
 * @property {string} userInput
 * @property {string} generatedQuery
 * @property {string} [optimizedQuery]  null until the Comparison Engine exists
 * @property {string} databaseType
 * @property {string} source            RULE_ENGINE | CACHE | OLLAMA (or GEMINI on rows predating the provider rename)
 * @property {string} createdAt
 */

/**
 * The Comparison Engine has no backend endpoint yet — these shapes describe
 * what comparison.js's mock produces for preview purposes only. Nothing
 * here is confirmed against real code because no real code exists.
 * @typedef {Object} CompareRequest
 * @property {string} originalQuery
 * @property {string} optimizedQuery
 * @property {object} originalAnalysis
 * @property {object} optimizedAnalysis
 * @property {{ruleId: string, explanation: string}[]} [changes]  structural edits mockSimulateOptimizedQuery actually made (column pruning, LIMIT), so ruleTrace can cite them — see mock/fixtures.js
 */

/**
 * @typedef {Object} CompareResponse
 * @property {number} estimatedCostDelta
 * @property {true} isEstimate
 * @property {{ruleId: string, explanation: string}[]} ruleTrace
 * @property {string} summary
 */

/** Which endpoints are confirmed against real, running backend code vs. simulated. */
export const ENDPOINT_STATUS = {
  queryBuilder: 'REAL',
  analysis: 'REAL',
  schemaAdvisor: 'REAL',
  history: 'REAL',
  comparison: 'SIMULATED', // no backend endpoint exists at all yet
};
