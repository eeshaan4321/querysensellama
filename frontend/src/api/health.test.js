import { describe, expect, it } from 'vitest';
import { probeBackend } from './health';

describe('probeBackend', () => {
  it('reports up, with the MongoDB status the backend gave', async () => {
    const probe = async () => ({ data: { status: 'UP', mongo: 'DOWN' } });
    expect(await probeBackend(probe)).toEqual({ status: 'up', mongo: 'DOWN' });
  });

  it('olderBackendHasNoMongoStatus: reports up with no MongoDB status when an older backend gives none', async () => {
    const probe = async () => ({ data: 'Backend is running' });
    expect(await probeBackend(probe)).toEqual({ status: 'up', mongo: null });
  });

  it('reports down when the probe rejects', async () => {
    const probe = async () => {
      throw new Error('Network Error');
    };
    expect(await probeBackend(probe)).toEqual({ status: 'down', mongo: null });
  });

  it('reports down when the probe rejects with an HTTP status', async () => {
    const probe = async () => {
      const err = new Error('Request failed with status code 500');
      err.response = { status: 500 };
      throw err;
    };
    expect(await probeBackend(probe)).toEqual({ status: 'down', mongo: null });
  });
});
