import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockAnalyzeQuery } from '../mock/fixtures';
import { TARGET_DB_TO_BACKEND } from './contracts';

export async function analyzeQuery({ query, targetDb, resourceLevel }) {
  if (USE_MOCK) {
    await mockDelay(600);
    return mockAnalyzeQuery({ query, targetDb, resourceLevel });
  }
  const { data } = await apiClient.post('/api/query/analyze', {
    query,
    databaseType: TARGET_DB_TO_BACKEND[targetDb],
    resourceLevel,
  });
  return data;
}
