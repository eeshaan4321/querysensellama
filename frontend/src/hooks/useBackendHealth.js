import { useCallback, useEffect, useRef, useState } from 'react';
import { checkBackendHealth } from '../api/health';
import { USE_MOCK } from '../api/client';

/** Polls the backend liveness probe so a dead API reads as one banner, not eight errors. */
export function useBackendHealth({
  check = checkBackendHealth,
  useMock = USE_MOCK,
  intervalMs = 20000,
} = {}) {
  const [status, setStatus] = useState(useMock ? 'mock' : 'unknown');
  const alive = useRef(true);

  const refresh = useCallback(async () => {
    if (useMock) return;
    const next = await check();
    if (alive.current) setStatus(next);
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

  return { status, refresh };
}
