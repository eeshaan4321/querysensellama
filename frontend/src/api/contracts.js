export const ResourceLevel = {
  LOW: 'LOW',
  MEDIUM: 'MEDIUM',
  HIGH: 'HIGH',
};

export const TargetDb = {
  SQL: 'SQL',
  MONGODB: 'MONGODB',
};

export const TARGET_DB_TO_BACKEND = {
  SQL: 'MySQL',
  MONGODB: 'MongoDB',
};

export const HandledBy = {
  RULE_ENGINE: 'RULE_ENGINE',
  CACHE: 'CACHE',
  OLLAMA: 'OLLAMA',
  GEMINI: 'GEMINI',
};

export function isLlmSource(handledBy) {
  return handledBy === HandledBy.OLLAMA || handledBy === HandledBy.GEMINI;
}

export function isGeneratedData(schema) {
  return Boolean(schema) && schema.builtIn === false;
}

export const SuggestionSeverity = {
  INFO: 'INFO',
  WARNING: 'WARNING',
  CRITICAL: 'CRITICAL',
};

export const ENDPOINT_STATUS = {
  queryBuilder: 'REAL',
  analysis: 'REAL',
  schemaAdvisor: 'REAL',
  history: 'REAL',
  schemaUpload: 'REAL',
  comparison: 'SIMULATED',
};
