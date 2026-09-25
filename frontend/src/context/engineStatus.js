import { createContext, useContext } from 'react';

export const EngineStatusContext = createContext(null);

export function useEngineStatus() {
  const ctx = useContext(EngineStatusContext);
  if (!ctx) throw new Error('useEngineStatus must be used within EngineStatusProvider');
  return ctx;
}
