import { useState, useCallback } from 'react';
import { EngineStatusContext } from './engineStatus.js';


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
