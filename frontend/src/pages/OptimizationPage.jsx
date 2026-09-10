import { useState } from 'react';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';
import { Button } from '../components/common/Button';
import { BeforeAfterDiff } from '../components/optimization/BeforeAfterDiff';
import { RuleTraceList } from '../components/optimization/RuleTraceList';
import { CostDeltaChart } from '../components/optimization/CostDeltaChart';
import { compareQueries } from '../api';
import { mockSimulateOptimizedQuery } from '../mock/fixtures';
import { useWorkbench } from '../context/WorkbenchContext';

/**
 * The Comparison Engine has no backend endpoint yet — it's Vedant's last
 * task, blocked on the other modules existing first (see CLAUDE.md). This
 * whole page is a labeled preview of what it will eventually show, built
 * from the real analysis this session already ran plus a simulated
 * "optimized" rewrite — never presented as a real result.
 */
export function OptimizationPage() {
  const { build, analysis, domain } = useWorkbench();
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState(null); // { optimizedQuery, rulesApplied }
  const [comparison, setComparison] = useState(null);

  async function runPreview() {
    setLoading(true);
    try {
      const sim = mockSimulateOptimizedQuery({
        generatedQuery: build.generatedQuery,
        tableName: analysis.tableName,
        domain,
      });
      setPreview(sim);

      if (analysis.databaseType === 'MySQL') {
        const compareResult = await compareQueries({
          originalQuery: build.generatedQuery,
          optimizedQuery: sim.optimizedQuery,
          originalAnalysis: analysis,
          optimizedAnalysis: { ...analysis, estimatedRows: 4, hasFullTableScan: false },
          changes: sim.changes,
        });
        setComparison(compareResult);
      }
    } finally {
      setLoading(false);
    }
  }

  if (!analysis) {
    return (
      <div>
        <PageHeader title="Optimization preview" description="A simulated preview of the Comparison Engine — not yet built." />
        <EmptyState title="Run an analysis first" detail="This preview builds on the Analysis page's real EXPLAIN or Mongo findings." />
      </div>
    );
  }

  if (analysis.databaseType !== 'MySQL') {
    return (
      <div>
        <PageHeader title="Optimization preview" description="A simulated preview of the Comparison Engine — not yet built." />
        <EmptyState
          title="SQL only, for now"
          detail="This preview's simulated rewrite and cost estimate are built around EXPLAIN row counts — there's nothing equivalent to preview for MongoDB yet."
        />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Optimization preview"
        description="The Comparison Engine doesn't exist on the backend yet — everything below is simulated for preview purposes, built on top of this session's real analysis."
        action={!preview && <Button onClick={runPreview} loading={loading}>Simulate optimization</Button>}
      />

      {!preview && !loading && (
        <EmptyState title="Not previewed yet" detail="Click Simulate optimization to see what a domain-aware rewrite might look like." />
      )}

      {preview && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-6)' }}>
          <div style={{
            fontSize: 'var(--text-xs)', color: 'var(--llm-tint)', background: 'var(--llm-wash)',
            border: '1px solid color-mix(in srgb, var(--llm) 35%, transparent)', borderRadius: 'var(--radius-sm)',
            padding: 'var(--space-2) var(--space-3)', width: 'fit-content',
          }}>
            Simulated — the Comparison Engine has no backend endpoint yet
          </div>

          <section>
            <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>Before / after (simulated)</h3>
            <BeforeAfterDiff before={build.generatedQuery} after={preview.optimizedQuery} />
          </section>

          {comparison && (
            <section style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-6)' }}>
              <div>
                <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                  Estimated cost (simulated)
                </h3>
                <CostDeltaChart before={analysis.estimatedRows ?? 0} after={4} />
              </div>
              <div>
                <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                  Why it would change
                </h3>
                <RuleTraceList trace={comparison.ruleTrace} />
              </div>
            </section>
          )}

          <section>
            <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>
              Simulated domain rules ({preview.domain.toLowerCase()})
            </h3>
            <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
              {preview.rulesApplied.map((r) => (
                <li key={r.ruleId} style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)' }}>
                  <code style={{ color: 'var(--rule-tint)', fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)' }}>{r.ruleId}</code>{' '}
                  {r.description}
                </li>
              ))}
            </ul>
          </section>

          {comparison && (
            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--ink-muted)', borderTop: '1px solid var(--border)', paddingTop: 'var(--space-4)' }}>
              {comparison.summary}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
