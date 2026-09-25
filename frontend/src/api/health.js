import { apiClient, USE_MOCK } from './client';

/** Liveness probe against GET /test — the only endpoint with no side effects. */
export async function probeBackend(probe) {
  try {
    await probe();
    return 'up';
  } catch {
    return 'down';
  }
}

export function checkBackendHealth() {
  if (USE_MOCK) return Promise.resolve('up');
  return probeBackend(() => apiClient.get('/api/health', { timeout: 4000 }));
}
