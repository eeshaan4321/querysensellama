import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { getActiveSchema, resetSchema } from '../api';

const ActiveSchemaContext = createContext(null);

export function ActiveSchemaProvider({ children }) {
  const [schema, setSchema] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSchema(await getActiveSchema());
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Could not read the active schema.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    getActiveSchema()
      .then((active) => {
        if (!cancelled) setSchema(active);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err.response?.data?.message || err.message || 'Could not read the active schema.');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const reset = useCallback(async () => {
    const restored = await resetSchema();
    setSchema(restored);
    return restored;
  }, []);

  const value = useMemo(
    () => ({ schema, loading, error, refresh, applySchema: setSchema, reset }),
    [schema, loading, error, refresh, reset]
  );

  return <ActiveSchemaContext.Provider value={value}>{children}</ActiveSchemaContext.Provider>;
}

export function useActiveSchema() {
  const ctx = useContext(ActiveSchemaContext);
  if (!ctx) throw new Error('useActiveSchema must be used within ActiveSchemaProvider');
  return ctx;
}
