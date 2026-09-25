import { apiClient, USE_MOCK, mockDelay } from './client';
import { TARGET_DB_TO_BACKEND, TargetDb } from './contracts';
import { mockCompare } from '../mock/fixtures';

export function toCompareRequest({ query, targetDb }) {
  return {
    query,
    databaseType: TARGET_DB_TO_BACKEND[targetDb] || TARGET_DB_TO_BACKEND[TargetDb.SQL],
  };
}

export async function compareQueries(payload) {
  if (USE_MOCK) {
    await mockDelay(500);
    return mockCompare(payload);
  }
  const { data } = await apiClient.post('/api/query/compare', toCompareRequest(payload));
  return data;
}
