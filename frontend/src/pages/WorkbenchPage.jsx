import { useState } from 'react';
import { PageHeader } from '../components/common/PageHeader';
import { QueryForm } from '../components/workbench/QueryForm';
import { QueryResultPanel } from '../components/workbench/QueryResultPanel';
import { EmptyState } from '../components/common/EmptyState';
import { buildQuery, postHistory } from '../api';
import { useEngineStatus } from '../context/engineStatus.js';
import { useWorkbench } from '../context/workbench.js';

export function WorkbenchPage() {
  const {
    resourceLevel, setResourceLevel, targetDb, setTargetDb,
    nlInput, setNlInput,
    build, setBuild, setAnalysis, setSuggestions, setSchemaAdvice,
  } = useWorkbench();
  const { report } = useEngineStatus();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(naturalLanguageQuery) {
    setLoading(true);
    setError(null);
    try {
      const response = await buildQuery({ naturalLanguageQuery, targetDb, resourceLevel });
      setBuild({ naturalLanguageQuery, ...response });
      setAnalysis(null);
      setSuggestions(null);
      setSchemaAdvice(null);
      report(response.handledBy, response.confidence);
      if (response.generatedQuery) postHistory({
        userInput: naturalLanguageQuery,
        generatedQuery: response.generatedQuery,
        databaseType: response.targetDb,
        source: response.handledBy,
      }).catch(() => {});
    } catch (err) {
      setError(err.message || 'Could not reach the query builder.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <PageHeader
        title="Query workbench"
        description="Describe what you need in plain language. The rule engine handles it directly when it can; ambiguous or complex phrasing falls back to the local LLM automatically."
      />

      <QueryForm
        resourceLevel={resourceLevel}
        setResourceLevel={setResourceLevel}
        targetDb={targetDb}
        setTargetDb={setTargetDb}
        text={nlInput}
        setText={setNlInput}
        onSubmit={handleSubmit}
        loading={loading}
      />

      <div style={{ marginTop: 'var(--space-5)' }}>
        {error && (
          <EmptyState title="Query build failed" detail={error} />
        )}
        {!error && build && <QueryResultPanel result={build} targetDb={build.targetDb} />}
        {!error && !build && (
          <EmptyState
            title="No query run yet"
            detail="Write a request above — try something simple first, then something with a comparison or a time range to see the fallback path light up."
          />
        )}
      </div>
    </div>
  );
}
