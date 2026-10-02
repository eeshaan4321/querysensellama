import { useCallback, useEffect, useRef, useState } from 'react';
import { checkBackendHealth } from '../api/health';
import { USE_MOCK } from '../api/client';

/** Polls the backend liveness probe so a dead API, or a dead MongoDB, reads as one banner. */
export function useBackendHealth({
  check = checkBackendHealth,
  useMock = USE_MOCK,
  intervalMs = 20000,
} = {}) {
  const [status, setStatus] = useState(useMock ? 'mock' : 'unknown');
  const [mongo, setMongo] = useState(null);
  const alive = useRef(true);

  const refresh = useCallback(async () => {
    if (useMock) return;
    const next = await check();
    const health = typeof next === 'string' ? { status: next, mongo: null } : next;
    if (alive.current) {
      setStatus(health.status);
      setMongo(health.mongo ?? null);
    }
  }, [check, useMock]);

  useEffect(() => {
    alive.current = true;
    if (useMock) return undefined;
    refresh();
    const id = setInterval(refresh, intervalMs);
    return () => {
      alive.current = false;
      clearInterval(id);
    };
  }, [refresh, useMock, intervalMs]);

  return { status, mongo, refresh };
}
