import { createContext, useContext, useState, useCallback } from 'react';

const EngineStatusContext = createContext(null);

export function EngineStatusProvider({ children }) {
  const [status, setStatus] = useState(null);

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
