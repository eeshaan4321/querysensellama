import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';
import { CodeBlock } from '../components/common/CodeBlock';
import { Button } from '../components/common/Button';
import { ExplainPlanView } from '../components/analysis/ExplainPlanView';
import { PlanTreeView } from '../components/analysis/PlanTreeView';
import { VerdictBanner } from '../components/analysis/VerdictBanner';
import { RuleTracePanel } from '../components/analysis/RuleTracePanel';
import { SuggestionsList } from '../components/analysis/SuggestionsList';
import { analyzeQuery } from '../api';
import { isMongoAnalysis } from '../api/contracts';
import { useWorkbench } from '../context/workbench.js';

export function AnalysisPage() {
  const { build, targetDb, resourceLevel, analysis, suggestions, setAnalysis, setSuggestions } = useWorkbench();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function runAnalysis() {
    setLoading(true);
    setError(null);
    try {
      const result = await analyzeQuery({
        query: build.generatedQuery,
        targetDb: build.targetDb || targetDb,
        resourceLevel,
      });
      setAnalysis(result.analysis);
      setSuggestions(result.suggestions);
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Analysis failed.');
    } finally {
      setLoading(false);
    }
  }

  if (!build) {
    return (
      <div>
        <PageHeader title="Analysis" description="Runs the real plan — MySQL EXPLAIN or MongoDB explain — and the optimization and resource-aware suggestions those findings trigger." />
        <EmptyState title="Nothing to analyze yet" detail="Build a query in the Workbench first." />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Analysis"
        description="Runs the real plan — MySQL EXPLAIN or MongoDB explain — and the optimization and resource-aware suggestions those findings trigger."
        action={!analysis && <Button onClick={runAnalysis} loading={loading}>Run analysis</Button>}
      />

      <div style={{ marginBottom: 'var(--space-5)' }}>
        <CodeBlock code={build.generatedQuery} dialect={build.targetDb} label="Analyzing" />
      </div>

      {error && <EmptyState title="Analysis failed" detail={error} />}

      {!analysis && !loading && !error && (
        <EmptyState title="Analysis not run yet" detail="Click Run analysis to send this query through the analysis engine." />
      )}

      {analysis && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-6)' }}>
          <VerdictBanner verdict={analysis.verdict} />

          <RuleTracePanel trace={build.ruleTrace} handledBy={build.handledBy} interpretations={build.interpretations} />

          {analysis.warnings?.length > 0 && (
            <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
              {analysis.warnings.map((w, i) => (
                <li key={i} style={{ fontSize: 'var(--text-sm)', color: 'var(--llm-tint)' }}>
                  {w}
                </li>
              ))}
            </ul>
          )}

          <ExplainPlanView plan={analysis} />

          <section>
            <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>
              Plan tree
            </h3>
            <p className="page-note" style={{ marginBottom: 'var(--space-3)' }}>
              {isMongoAnalysis(analysis)
                ? 'The same plan as above, as MongoDB executes it: each stage feeds the one that wraps it, and a sort or a $lookup appears as its own stage.'
                : 'The same plan as above, in the shape MySQL actually executes it: operations wrap the tables they drive, and a sort is attributed to the step that performs it.'}
            </p>
            <PlanTreeView tree={analysis.planTree} dialect={isMongoAnalysis(analysis) ? 'MONGODB' : 'SQL'} />
          </section>

          <section>
            <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
              Optimization &amp; resource suggestions
            </h3>
            <SuggestionsList suggestions={suggestions} />
          </section>

          <div>
            <Button variant="secondary" onClick={() => navigate('/schema')}>
              Continue to schema advisor
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
