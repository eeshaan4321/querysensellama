import { apiClient, USE_MOCK } from './client';

/** Liveness probe against GET /api/health, with MongoDB's status when the backend reports it. */
export function readHealth(data) {
  return { status: 'up', mongo: data?.mongo ?? null };
}

export async function probeBackend(probe) {
  try {
    const response = await probe();
    return readHealth(response?.data);
  } catch {
    return { status: 'down', mongo: null };
  }
}

export function checkBackendHealth() {
  if (USE_MOCK) return Promise.resolve({ status: 'up', mongo: 'UP' });
  return probeBackend(() => apiClient.get('/api/health', { timeout: 4000 }));
}
