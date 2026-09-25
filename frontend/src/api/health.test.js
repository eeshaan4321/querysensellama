import { describe, expect, it } from 'vitest';
import { probeBackend } from './health';

describe('probeBackend', () => {
  it('reports up when the probe resolves', async () => {
    const probe = async () => ({ data: 'Backend is running' });
    expect(await probeBackend(probe)).toBe('up');
  });

  it('reports down when the probe rejects', async () => {
    const probe = async () => {
      throw new Error('Network Error');
    };
    expect(await probeBackend(probe)).toBe('down');
  });

  it('reports down when the probe rejects with an HTTP status', async () => {
    const probe = async () => {
      const err = new Error('Request failed with status code 500');
      err.response = { status: 500 };
      throw err;
    };
    expect(await probeBackend(probe)).toBe('down');
  });

  it('reports up even when the endpoint returns an empty body', async () => {
    const probe = async () => ({ data: '' });
    expect(await probeBackend(probe)).toBe('up');
  });
});
