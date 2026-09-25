import { createContext, useContext } from 'react';

export const ActiveSchemaContext = createContext(null);

export function useActiveSchema() {
  const ctx = useContext(ActiveSchemaContext);
  if (!ctx) throw new Error('useActiveSchema must be used within ActiveSchemaProvider');
  return ctx;
}
