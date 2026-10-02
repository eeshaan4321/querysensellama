import { renderHook, waitFor, act } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useBackendHealth } from './useBackendHealth';

describe('useBackendHealth', () => {
  it('starts unknown so the shell shows nothing before the first probe', () => {
    const { result } = renderHook(() => useBackendHealth({ check: () => new Promise(() => {}) }));
    expect(result.current.status).toBe('unknown');
  });

  it('settles on the probe result', async () => {
    const { result } = renderHook(() => useBackendHealth({ check: async () => 'down' }));
    await waitFor(() => expect(result.current.status).toBe('down'));
  });

  it('re-probes on refresh', async () => {
    const check = vi.fn().mockResolvedValueOnce('down').mockResolvedValueOnce('up');
    const { result } = renderHook(() => useBackendHealth({ check }));

    await waitFor(() => expect(result.current.status).toBe('down'));
    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.status).toBe('up');
    expect(check).toHaveBeenCalledTimes(2);
  });

  it('reports mock mode instead of probing', async () => {
    const check = vi.fn();
    const { result } = renderHook(() => useBackendHealth({ check, useMock: true }));

    await waitFor(() => expect(result.current.status).toBe('mock'));
    expect(check).not.toHaveBeenCalled();
  });

  it('reports MongoDB status alongside the backend status', async () => {
    const { result } = renderHook(() => useBackendHealth({ check: async () => ({ status: 'up', mongo: 'DOWN' }) }));

    await waitFor(() => expect(result.current.mongo).toBe('DOWN'));
    expect(result.current.status).toBe('up');
  });
});
