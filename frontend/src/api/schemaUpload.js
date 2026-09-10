import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockActiveSchema, mockResetSchema, mockSchemaUpload } from '../mock/fixtures';

/**
 * POST /api/schema/upload — multipart form.
 *
 * WHY Content-Type IS SET TO undefined RATHER THAN 'multipart/form-data':
 * apiClient sets 'application/json' on every request. A multipart body needs
 * a boundary parameter that only the browser can generate, so the header has
 * to be removed and left to the browser. Overwriting it with a literal
 * 'multipart/form-data' produces a boundary-less header and the backend
 * rejects the request as malformed.
 *
 * @param {File} file
 * @returns {Promise<import('./contracts').SchemaSnapshot>}
 */
export async function uploadSchemaFile(file) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl: await file.text(), label: file.name });
  }
  const form = new FormData();
  form.append('file', file);
  const { data } = await apiClient.post('/api/schema/upload', form, {
    headers: { 'Content-Type': undefined },
  });
  return data;
}

/**
 * POST /api/schema/upload — JSON form, for DDL pasted into the textarea.
 * @param {{ddl: string, label?: string}} payload
 * @returns {Promise<import('./contracts').SchemaSnapshot>}
 */
export async function uploadSchemaText({ ddl, label }) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl, label: label || 'Pasted schema' });
  }
  const { data } = await apiClient.post('/api/schema/upload', { ddl, label });
  return data;
}

/**
 * GET /api/schema/active — which schema the backend is currently working
 * against. Called once at app start; the answer decides what the rule engine
 * recognises and which database EXPLAIN describes.
 * @returns {Promise<import('./contracts').SchemaSnapshot>}
 */
export async function getActiveSchema() {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockActiveSchema();
  }
  const { data } = await apiClient.get('/api/schema/active');
  return data;
}

/**
 * POST /api/schema/reset — back to the built-in demo schema. Instant on the
 * backend: the uploaded tables are left in their own database and simply
 * stop being the ones anything reads.
 * @returns {Promise<import('./contracts').SchemaSnapshot>}
 */
export async function resetSchema() {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockResetSchema();
  }
  const { data } = await apiClient.post('/api/schema/reset');
  return data;
}
