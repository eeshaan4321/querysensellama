import axios from 'axios';

export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false';

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: Number(import.meta.env.VITE_API_TIMEOUT) || 65000,
  headers: { 'Content-Type': 'application/json' },
});

export function mockDelay(ms = 450) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
