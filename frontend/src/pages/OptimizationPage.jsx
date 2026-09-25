import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';
import { Button } from '../components/common/Button';
import { BeforeAfterDiff } from '../components/optimization/BeforeAfterDiff';
import { RuleTraceList } from '../components/optimization/RuleTraceList';
import { CostDeltaChart } from '../components/optimization/CostDeltaChart';
import { ApplyScript } from '../components/optimization/ApplyScript';
import { RecommendationList } from '../components/schema/RecommendationList';
import { adviseSchema, compareQueries } from '../api';
import { TargetDb } from '../api/contracts';
import { mockSimulateOptimizedQuery } from '../mock/fixtures';
import { useWorkbench } from '../context/workbench.js';
import { useActiveSchema } from '../context/activeSchema.js';

export function OptimizationPage() {
  const { build, analysis, targetDb, resourceLevel, schemaAdvice, setSchemaAdvice } = useWorkbench();
  const { schema } = useActiveSchema();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState(null);
  const [comparison, setComparison] = useState(null);
  const [adviceLoading, setAdviceLoading] = useState(false);
  const [adviceError, setAdviceError] = useState(null);

  const activeDb = build?.targetDb || targetDb;
  const isMongo = activeDb === TargetDb.MONGODB;

  useEffect(() => {
    if (!analysis || schemaAdvice || adviceLoading) return;

    let cancelled = false;
    setAdviceLoading(true);
    setAdviceError(null);

    adviseSchema({
      query: build.generatedQuery,
      targetDb: activeDb,
      resourceLevel,
    })
      .then((result) => {
        if (!cancelled) setSchemaAdvice(result);
      })
      .catch((err) => {
        if (!cancelled) {
          setAdviceError(err.response?.data?.message || err.message || 'Schema advisor failed.');
        }
      })
      .finally(() => {
        if (!cancelled) setAdviceLoading(false);
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [analysis, schemaAdvice]);

  async function runPreview() {
    setLoading(true);
    try {
      const sim = mockSimulateOptimizedQuery({
        generatedQuery: build.generatedQuery,
        tableName: analysis.tableName,
      });
      setPreview(sim);

      const compareResult = await compareQueries({
        originalQuery: build.generatedQuery,
        optimizedQuery: sim.optimizedQuery,
        originalAnalysis: analysis,
        optimizedAnalysis: { ...analysis, estimatedRows: 4, hasFullTableScan: false },
        changes: sim.changes,
      });
      setComparison(compareResult);
    } finally {
      setLoading(false);
    }
  }

  if (!analysis) {
    return (
      <div>
        <PageHeader
          title="Optimization"
          description="Schema changes the advisor found for this query, plus a labelled preview of the rewrite engine that doesn't exist yet."
        />
        <EmptyState title="Run an analysis first" detail="Both halves of this page build on the Analysis page's EXPLAIN or Mongo findings." />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Optimization"
        description="Two halves. The schema changes below are real — every one is anchored to an EXPLAIN finding, and QuerySense recommends them without ever running them for you. The rewrite preview under them is simulated; the Comparison Engine has no backend endpoint yet."
      />

      <section style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
        <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', margin: 0 }}>
          Recommended schema changes
        </h3>

        {schema && !schema.builtIn && (
          <p className="page-note">
            Anchored to EXPLAIN over your uploaded schema, whose tables hold generated rows.
          </p>
        )}

        {adviceLoading && <p className="page-note">Reading the schema advisor…</p>}

        {adviceError && <EmptyState title="Schema advisor failed" detail={adviceError} />}

        {!adviceLoading && !adviceError && schemaAdvice?.length === 0 && (
          <EmptyState
            title="No schema changes recommended"
            detail={
              schema?.builtIn
                ? "This query didn't trigger an indexing or structural recommendation. The built-in demo tables hold 5–49 rows, so most queries never produce a finding worth acting on — upload a schema to see the advisor work against realistic volume."
                : "This query didn't trigger an indexing, normalization, or structural recommendation."
            }
            action={
              schema?.builtIn && (
                <Button variant="secondary" onClick={() => navigate('/schema-source')}>
                  Upload a schema
                </Button>
              )
            }
          />
        )}

        {schemaAdvice?.length > 0 && (
          <>
            <RecommendationList recommendations={schemaAdvice} />
            <ApplyScript
              recommendations={schemaAdvice}
              databaseType={isMongo ? 'MongoDB' : 'MySQL'}
              catalog={schema?.catalog ?? null}
            />
          </>
        )}
      </section>

      <section
        style={{
          marginTop: 'var(--space-7)',
          paddingTop: 'var(--space-6)',
          borderTop: '1px solid var(--border)',
          display: 'flex',
          flexDirection: 'column',
          gap: 'var(--space-4)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 'var(--space-3)' }}>
          <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', margin: 0 }}>
            Query rewrite preview
          </h3>
          {!isMongo && !preview && (
            <Button onClick={runPreview} loading={loading}>Simulate rewrite</Button>
          )}
        </div>

        <div style={{
          fontSize: 'var(--text-xs)', color: 'var(--llm-tint)', background: 'var(--llm-wash)',
          border: '1px solid color-mix(in srgb, var(--llm) 35%, transparent)', borderRadius: 'var(--radius-sm)',
          padding: 'var(--space-2) var(--space-3)', width: 'fit-content',
        }}>
          Simulated — the Comparison Engine has no backend endpoint yet
        </div>

        {isMongo ? (
          <EmptyState
            title="SQL only, for now"
            detail="This preview's simulated rewrite and cost estimate are built around EXPLAIN row counts — there's nothing equivalent to preview for MongoDB yet. The schema changes above are real and do apply to MongoDB."
          />
        ) : !preview && !loading ? (
          <EmptyState title="Not previewed yet" detail="Click Simulate rewrite to see what a rewrite might look like." />
        ) : preview ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-6)' }}>
            <section>
              <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>Before / after (simulated)</h4>
              <BeforeAfterDiff before={build.generatedQuery} after={preview.optimizedQuery} />
            </section>

            {comparison && (
              <section style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-6)' }}>
                <div>
                  <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                    Estimated cost (simulated)
                  </h4>
                  <CostDeltaChart before={analysis.estimatedRows ?? 0} after={4} />
                </div>
                <div>
                  <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                    Why it would change
                  </h4>
                  <RuleTraceList trace={comparison.ruleTrace} />
                </div>
              </section>
            )}

            <section>
              <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>
                Simulated rewrite rules
              </h4>
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
        ) : null}
      </section>
    </div>
  );
}
