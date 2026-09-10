import { createContext, useContext, useState, useCallback } from 'react';

const EngineStatusContext = createContext(null);

/**
 * Tracks which engine handled the most recent query, shared between the
 * persistent shell header and the Workbench. This is the seam that lets
 * the whole app "shift into amber mode" the instant a query falls back to
 * Gemini, not just log it after the fact.
 */
export function EngineStatusProvider({ children }) {
  const [status, setStatus] = useState(null); // { handledBy, confidence, at }

  const report = useCallback((handledBy, confidence) => {
    setStatus({ handledBy, confidence, at: Date.now() });
  }, []);

  return (
    <EngineStatusContext.Provider value={{ status, report }}>
      {children}
    </EngineStatusContext.Provider>
  );
}

export function useEngineStatus() {
  const ctx = useContext(EngineStatusContext);
  if (!ctx) throw new Error('useEngineStatus must be used within EngineStatusProvider');
  return ctx;
}
