import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockSchemaAdvise } from '../mock/fixtures';
import { TARGET_DB_TO_BACKEND } from './contracts';

export async function adviseSchema({ query, targetDb, resourceLevel }) {
  if (USE_MOCK) {
    await mockDelay(450);
    return mockSchemaAdvise({ query, targetDb, resourceLevel });
  }
  const { data } = await apiClient.post('/api/query/schema-advisor', {
    query,
    databaseType: TARGET_DB_TO_BACKEND[targetDb],
    resourceLevel,
  });
  return data;
}
