import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockActiveSchema, mockResetSchema, mockSchemaUpload } from '../mock/fixtures';

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

export async function uploadSchemaText({ ddl, label }) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl, label: label || 'Pasted schema' });
  }
  const { data } = await apiClient.post('/api/schema/upload', { ddl, label });
  return data;
}

export async function getActiveSchema() {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockActiveSchema();
  }
  const { data } = await apiClient.get('/api/schema/active');
  return data;
}

export async function resetSchema() {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockResetSchema();
  }
  const { data } = await apiClient.post('/api/schema/reset');
  return data;
}
