import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockGetHistory, mockPostHistory } from '../mock/fixtures';

/**
 * GET /api/query/history — REAL, verified against the running backend.
 * @returns {Promise<{records: import('./contracts').HistoryRecord[]}>}
 */
export async function getHistory() {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockGetHistory();
  }
  const { data } = await apiClient.get('/api/query/history');
  return { records: data };
}

/**
 * There is no POST /api/query/history on the real backend — history is
 * saved automatically, server-side, as a side effect of /api/query/generate
 * (see QueryGenerationServiceImpl.saveToHistory). This function is a no-op
 * in real mode; it only exists so mock mode can simulate a growing history
 * list without a backend, and so callers don't need an if(USE_MOCK) check
 * of their own.
 * @param {Partial<import('./contracts').HistoryRecord>} entry
 */
export async function postHistory(entry) {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockPostHistory(entry);
  }
  return null;
}
