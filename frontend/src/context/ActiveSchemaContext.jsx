import { useCallback, useEffect, useMemo, useState } from 'react';

import { getActiveSchema, resetSchema } from '../api';
import { TargetDb, targetDbOf } from '../api/contracts';
import { ActiveSchemaContext } from './activeSchema.js';
import { useWorkbench } from './workbench.js';

function messageOf(err) {
  return err?.response?.data?.message || err?.message || 'Could not read the active schema.';
}

export function ActiveSchemaProvider({ children }) {
  const { targetDb } = useWorkbench();
  const [schemas, setSchemas] = useState({ [TargetDb.SQL]: null, [TargetDb.MONGODB]: null });
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState({ [TargetDb.SQL]: null, [TargetDb.MONGODB]: null });

  useEffect(() => {
    let cancelled = false;

    Promise.allSettled([getActiveSchema(TargetDb.SQL), getActiveSchema(TargetDb.MONGODB)]).then(([sql, mongo]) => {
      if (cancelled) return;
      setSchemas({
        [TargetDb.SQL]: sql.status === 'fulfilled' ? sql.value : null,
        [TargetDb.MONGODB]: mongo.status === 'fulfilled' ? mongo.value : null,
      });
      setErrors({
        [TargetDb.SQL]: sql.status === 'rejected' ? messageOf(sql.reason) : null,
        [TargetDb.MONGODB]: mongo.status === 'rejected' ? messageOf(mongo.reason) : null,
      });
      setLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, []);

  const applySchema = useCallback((snapshot) => {
    if (!snapshot) return;
    setSchemas((previous) => ({ ...previous, [targetDbOf(snapshot)]: snapshot }));
  }, []);

  const refresh = useCallback(async (db = targetDb) => {
    setLoading(true);
    setErrors((previous) => ({ ...previous, [db]: null }));
    try {
      applySchema(await getActiveSchema(db));
    } catch (err) {
      setErrors((previous) => ({ ...previous, [db]: messageOf(err) }));
    } finally {
      setLoading(false);
    }
  }, [targetDb, applySchema]);

  const reset = useCallback(async (db = targetDb) => {
    const restored = await resetSchema(db);
    applySchema(restored);
    return restored;
  }, [targetDb, applySchema]);

  const schemaFor = useCallback((db) => schemas[db] ?? null, [schemas]);
  const errorFor = useCallback((db) => errors[db] ?? null, [errors]);

  const value = useMemo(
    () => ({
      schema: schemas[targetDb] ?? null,
      schemas,
      schemaFor,
      loading,
      error: errors[targetDb] ?? null,
      errorFor,
      refresh,
      applySchema,
      reset,
    }),
    [schemas, targetDb, schemaFor, loading, errors, errorFor, refresh, applySchema, reset],
  );

  return <ActiveSchemaContext.Provider value={value}>{children}</ActiveSchemaContext.Provider>;
}
