import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockBuildQuery } from '../mock/fixtures';
import { TARGET_DB_TO_BACKEND } from './contracts';

/**
 * POST /api/query/generate — REAL, verified against the running backend.
 * Note the real request has no `domain` field — the rule engine and Gemini
 * fallback don't take one; domain only matters once you get to /analyze.
 * @param {{naturalLanguageQuery: string, targetDb: 'SQL'|'MONGODB'}} payload
 * @returns {Promise<import('./contracts').QueryBuildResponse>}
 */
export async function buildQuery({ naturalLanguageQuery, targetDb }) {
  if (USE_MOCK) {
    await mockDelay();
    return mockBuildQuery({ naturalLanguageQuery, targetDb });
  }
  const { data } = await apiClient.post('/api/query/generate', {
    query: naturalLanguageQuery,
    databaseType: TARGET_DB_TO_BACKEND[targetDb],
  });
  return {
    generatedQuery: data.generatedQuery,
    targetDb,
    handledBy: data.handledBy,
    confidence: data.confidence,
  };
}
