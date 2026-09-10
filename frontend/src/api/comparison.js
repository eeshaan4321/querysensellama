import { mockDelay } from './client';
import { mockCompare } from '../mock/fixtures';

/**
 * There is no POST /api/query/compare — the Comparison Engine is Vedant's
 * last task, blocked on the other modules existing first (see CLAUDE.md).
 * This always returns simulated data, regardless of VITE_USE_MOCK, because
 * there is no real endpoint to fall back to yet. Swap this for a real
 * apiClient.post call once that endpoint exists.
 * @param {import('./contracts').CompareRequest} payload
 * @returns {Promise<import('./contracts').CompareResponse>}
 */
export async function compareQueries(payload) {
  await mockDelay(500);
  return mockCompare(payload);
}
