import axios from 'axios';

export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false';

// 15s used to be the default here, which was shorter than the backend's own
// patience: http.client.read-timeout-ms is 60000, because the LLM fallback
// runs a local qwen2.5-coder:7b through Ollama and a cold first token can
// take tens of seconds. A client timeout below that aborts the request while
// the backend is still generating, so the user sees a network error for a
// call that was about to succeed. 65s clears the backend's ceiling.
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: Number(import.meta.env.VITE_API_TIMEOUT) || 65000,
  headers: { 'Content-Type': 'application/json' },
});

/** Shared artificial latency so mock mode still feels like a network call. */
export function mockDelay(ms = 450) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
