import { apiClient, USE_MOCK, mockDelay } from './client';
import { TARGET_DB_TO_BACKEND, TargetDb } from './contracts';
import { mockActiveSchema, mockResetSchema, mockSchemaUpload } from '../mock/fixtures';

export function dialectParam(targetDb) {
  return TARGET_DB_TO_BACKEND[targetDb] || TARGET_DB_TO_BACKEND[TargetDb.SQL];
}

export function toUploadForm(file, targetDb) {
  const form = new FormData();
  form.append('file', file);
  form.append('dialect', dialectParam(targetDb));
  return form;
}

export function toUploadBody({ ddl, label }, targetDb) {
  return { ddl, label, dialect: dialectParam(targetDb) };
}

export async function uploadSchemaFile(file, targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl: await file.text(), label: file.name, targetDb });
  }
  const { data } = await apiClient.post('/api/schema/upload', toUploadForm(file, targetDb), {
    headers: { 'Content-Type': undefined },
  });
  return data;
}

export async function uploadSchemaText({ ddl, label }, targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl, label: label || 'Pasted schema', targetDb });
  }
  const { data } = await apiClient.post('/api/schema/upload', toUploadBody({ ddl, label }, targetDb));
  return data;
}

export async function getActiveSchema(targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockActiveSchema(targetDb);
  }
  const { data } = await apiClient.get('/api/schema/active', { params: { dialect: dialectParam(targetDb) } });
  return data;
}

export async function resetSchema(targetDb = TargetDb.SQL) {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockResetSchema(targetDb);
  }
  const { data } = await apiClient.post('/api/schema/reset', null, { params: { dialect: dialectParam(targetDb) } });
  return data;
}
