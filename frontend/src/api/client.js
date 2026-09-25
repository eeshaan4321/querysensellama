import axios from 'axios';

/** Mock fixtures are opt-in: anything but an explicit yes hits the real backend. */
export function resolveUseMock(raw) {
  const value = String(raw ?? '').trim().toLowerCase();
  return value === 'true' || value === '1';
}

export const USE_MOCK = resolveUseMock(import.meta.env.VITE_USE_MOCK);

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: Number(import.meta.env.VITE_API_TIMEOUT) || 65000,
  headers: { 'Content-Type': 'application/json' },
});

export function mockDelay(ms = 450) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
