import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockSchemaAdvise } from '../mock/fixtures';
import { TARGET_DB_TO_BACKEND, DOMAIN_TO_BACKEND_PROFILE } from './contracts';

/**
 * POST /api/query/schema-advisor — REAL, verified against the running
 * backend (note the URL: schema-advisor, not schema/advise). Takes the
 * same request shape as /analyze and re-runs analysis internally rather
 * than accepting a QueryAnalysisResult directly — see the backend
 * controller's own doc comment for why. domainProfile/resourceLevel are
 * required by validation but not used by this endpoint's own logic.
 * @param {{query: string, targetDb: 'SQL'|'MONGODB', domain: string, resourceLevel: string}} payload
 * @returns {Promise<import('./contracts').SchemaRecommendation[]>}
 */
export async function adviseSchema({ query, targetDb, domain, resourceLevel }) {
  if (USE_MOCK) {
    await mockDelay(450);
    return mockSchemaAdvise({ query, targetDb, domain, resourceLevel });
  }
  const { data } = await apiClient.post('/api/query/schema-advisor', {
    query,
    databaseType: TARGET_DB_TO_BACKEND[targetDb],
    domainProfile: DOMAIN_TO_BACKEND_PROFILE[domain],
    resourceLevel,
  });
  return data; // already a raw array
}
