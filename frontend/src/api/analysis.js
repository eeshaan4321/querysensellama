import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockAnalyzeQuery } from '../mock/fixtures';
import { TARGET_DB_TO_BACKEND, DOMAIN_TO_BACKEND_PROFILE } from './contracts';

/**
 * POST /api/query/analyze — REAL, verified against the running backend.
 * Requires domainProfile + resourceLevel now (the backend added this
 * requirement when the Domain/Resource modules were wired in) even though
 * this screen's job is analysis, not optimization — the response's
 * `suggestions` array is exactly those two modules' output, returned
 * together with the raw analysis in one call.
 * @param {{query: string, targetDb: 'SQL'|'MONGODB', domain: string, resourceLevel: string}} payload
 * @returns {Promise<import('./contracts').AnalyzeResponse>}
 */
export async function analyzeQuery({ query, targetDb, domain, resourceLevel }) {
  if (USE_MOCK) {
    await mockDelay(600);
    return mockAnalyzeQuery({ query, targetDb, domain, resourceLevel });
  }
  const { data } = await apiClient.post('/api/query/analyze', {
    query,
    databaseType: TARGET_DB_TO_BACKEND[targetDb],
    domainProfile: DOMAIN_TO_BACKEND_PROFILE[domain],
    resourceLevel,
  });
  return data; // already { analysis, suggestions }
}
