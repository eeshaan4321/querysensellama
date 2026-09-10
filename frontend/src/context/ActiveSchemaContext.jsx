import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { getActiveSchema, resetSchema } from '../api';

const ActiveSchemaContext = createContext(null);

/**
 * Which schema the whole app is currently working against.
 *
 * WHY THIS IS GLOBAL AND FETCHED AT STARTUP:
 * The active schema decides what the rule engine recognises, what the LLM
 * prompt is grounded on, and which database EXPLAIN describes. A user who
 * cannot tell which schema is live cannot trust any screen — so it is
 * fetched once here and shown permanently in the status bar, exactly like
 * the engine indicator beside it.
 *
 * WHY A FAILED FETCH IS NOT FATAL:
 * A backend that is down should not blank the app. The schema stays null,
 * the status bar says "Schema unknown", and every other screen fails
 * per-request the way it already does.
 */
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

  // The mount fetch is written out rather than calling refresh(), for two
  // reasons: refresh() would set state synchronously inside the effect
  // (loading already starts true, so that call is redundant anyway), and a
  // response arriving after unmount would otherwise set state on a dead
  // component. State here is only ever touched from an async callback, and
  // only while still mounted.
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
