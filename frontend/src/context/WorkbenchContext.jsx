import { createContext, useContext, useMemo, useState } from 'react';
import { Domain, ResourceLevel, TargetDb } from '../api/contracts';

const WorkbenchContext = createContext(null);

/**
 * Carries the query someone just built (plus their domain/resource/target-db
 * choices) across pages, so Analysis / Schema Advisor can act on "the query
 * I just wrote in the Workbench" instead of asking for it again on every
 * screen.
 *
 * `suggestions` holds the Domain + Resource optimization advice — both
 * modules are folded into one /analyze response on the real backend, so
 * there's a single suggestions list here, not separate domain/resource
 * slots like an earlier draft of this file assumed.
 */
export function WorkbenchProvider({ children }) {
  const [domain, setDomain] = useState(Domain.ECOMMERCE);
  const [resourceLevel, setResourceLevel] = useState(ResourceLevel.MEDIUM);
  const [targetDb, setTargetDb] = useState(TargetDb.SQL);

  // The raw text of the Workbench's NL textarea. This used to live as local
  // useState inside QueryForm, which meant it reset to '' every time
  // WorkbenchPage unmounted (i.e. any navigation away and back) even though
  // the *result* of running it (`build`) already persisted here. Lifted up
  // so the text a user typed survives navigation the same way the query
  // it produced does.
  const [nlInput, setNlInput] = useState('');

  const [build, setBuild] = useState(null); // { naturalLanguageQuery, ...QueryBuildResponse }
  const [analysis, setAnalysis] = useState(null); // QueryAnalysisResult
  const [suggestions, setSuggestions] = useState(null); // OptimizationSuggestion[]
  const [schemaAdvice, setSchemaAdvice] = useState(null); // SchemaRecommendation[]

  const value = useMemo(
    () => ({
      domain, setDomain,
      resourceLevel, setResourceLevel,
      targetDb, setTargetDb,
      nlInput, setNlInput,
      build, setBuild,
      analysis, setAnalysis,
      suggestions, setSuggestions,
      schemaAdvice, setSchemaAdvice,
    }),
    [domain, resourceLevel, targetDb, nlInput, build, analysis, suggestions, schemaAdvice]
  );

  return <WorkbenchContext.Provider value={value}>{children}</WorkbenchContext.Provider>;
}

export function useWorkbench() {
  const ctx = useContext(WorkbenchContext);
  if (!ctx) throw new Error('useWorkbench must be used within WorkbenchProvider');
  return ctx;
}
