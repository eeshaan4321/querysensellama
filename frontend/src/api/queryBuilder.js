import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockBuildQuery } from '../mock/fixtures';
import { TARGET_DB_TO_BACKEND } from './contracts';

export async function buildQuery({ naturalLanguageQuery, targetDb, resourceLevel }) {
  if (USE_MOCK) {
    await mockDelay();
    return mockBuildQuery({ naturalLanguageQuery, targetDb, resourceLevel });
  }
  const { data } = await apiClient.post('/api/query/generate', {
    query: naturalLanguageQuery,
    databaseType: TARGET_DB_TO_BACKEND[targetDb],
    resourceLevel,
  });
  return {
    generatedQuery: data.generatedQuery,
    targetDb,
    handledBy: data.handledBy,
    confidence: data.confidence,
    ruleTrace: data.ruleTrace ?? null,
    schemaWarning: data.schemaWarning ?? null,
  };
}
