import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockGetHistory, mockPostHistory } from '../mock/fixtures';

export async function getHistory() {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockGetHistory();
  }
  const { data } = await apiClient.get('/api/query/history');
  return { records: data };
}

export async function postHistory(entry) {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockPostHistory(entry);
  }
  return null;
}
