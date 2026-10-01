import { describe, expect, it } from 'vitest';
import { dialectParam, toUploadBody, toUploadForm } from './schemaUpload';

describe('schema upload requests', () => {
  it('names the database for every call, MySQL by default', () => {
    expect(dialectParam('MONGODB')).toBe('MongoDB');
    expect(dialectParam('SQL')).toBe('MySQL');
    expect(dialectParam(undefined)).toBe('MySQL');
  });

  it('sends pasted text as ddl with its database', () => {
    expect(toUploadBody({ ddl: '{"collections": []}', label: 'Pasted schema' }, 'MONGODB')).toEqual({
      ddl: '{"collections": []}',
      label: 'Pasted schema',
      dialect: 'MongoDB',
    });
  });

  it('sends a file with a dialect part', () => {
    const form = toUploadForm(new File(['{}'], 'shop.json'), 'MONGODB');

    expect(form.get('dialect')).toBe('MongoDB');
    expect(form.get('file').name).toBe('shop.json');
  });
});
