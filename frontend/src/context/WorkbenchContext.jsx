import { createContext, useContext, useMemo, useState } from 'react';
import { ResourceLevel, TargetDb } from '../api/contracts';

const WorkbenchContext = createContext(null);

export function WorkbenchProvider({ children }) {
  const [resourceLevel, setResourceLevel] = useState(ResourceLevel.MEDIUM);
  const [targetDb, setTargetDb] = useState(TargetDb.SQL);

  const [nlInput, setNlInput] = useState('');

  const [build, setBuild] = useState(null);
  const [analysis, setAnalysis] = useState(null);
  const [suggestions, setSuggestions] = useState(null);
  const [schemaAdvice, setSchemaAdvice] = useState(null);

  const value = useMemo(
    () => ({
      resourceLevel, setResourceLevel,
      targetDb, setTargetDb,
      nlInput, setNlInput,
      build, setBuild,
      analysis, setAnalysis,
      suggestions, setSuggestions,
      schemaAdvice, setSchemaAdvice,
    }),
    [resourceLevel, targetDb, nlInput, build, analysis, suggestions, schemaAdvice]
  );

  return <WorkbenchContext.Provider value={value}>{children}</WorkbenchContext.Provider>;
}

export function useWorkbench() {
  const ctx = useContext(WorkbenchContext);
  if (!ctx) throw new Error('useWorkbench must be used within WorkbenchProvider');
  return ctx;
}
