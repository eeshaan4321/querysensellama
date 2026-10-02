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
import { useWorkbench } from '../context/workbench.js';
import { useActiveSchema } from '../context/activeSchema.js';

export function OptimizationPage() {
  const { build, analysis, targetDb, resourceLevel, schemaAdvice, setSchemaAdvice } = useWorkbench();
  const { schemaFor } = useActiveSchema();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(false);
  const [comparison, setComparison] = useState(null);
  const [compareError, setCompareError] = useState(null);
  const [adviceLoading, setAdviceLoading] = useState(false);
  const [adviceError, setAdviceError] = useState(null);

  const activeDb = build?.targetDb || targetDb;
  const isMongo = activeDb === TargetDb.MONGODB;
  const schema = schemaFor(activeDb);

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

  async function runComparison() {
    setLoading(true);
    setCompareError(null);
    try {
      const result = await compareQueries({
        query: build.generatedQuery,
        targetDb: activeDb,
        userInput: build.naturalLanguageQuery,
      });
      setComparison(result);
    } catch (err) {
      setCompareError(err.response?.data?.message || err.message || 'Comparison failed.');
    } finally {
      setLoading(false);
    }
  }

  if (!analysis) {
    return (
      <div>
        <PageHeader
          title="Optimization"
          description="Schema changes the advisor found for this query, plus the rewrite the engine can apply to the query itself."
        />
        <EmptyState title="Run an analysis first" detail="Both halves of this page build on the Analysis page's EXPLAIN or Mongo findings." />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Optimization"
        description="Two halves, optimising different things. The schema changes below change the database and leave the query alone; the rewrite under them changes the query and leaves the database alone. QuerySense recommends both without ever running either for you."
      />

      <section style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
        <h3 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', margin: 0 }}>
          Recommended schema changes
        </h3>

        {schema && !schema.builtIn && (
          <p className="page-note">
            {isMongo
              ? 'Anchored to MongoDB explain over your uploaded collections, which hold generated documents.'
              : 'Anchored to EXPLAIN over your uploaded schema, whose tables hold generated rows.'}
          </p>
        )}

        {adviceLoading && <p className="page-note">Reading the schema advisor…</p>}

        {adviceError && <EmptyState title="Schema advisor failed" detail={adviceError} />}

        {!adviceLoading && !adviceError && schemaAdvice?.length === 0 && (
          <EmptyState
            title="No schema changes recommended"
            detail={
              schema?.builtIn && !isMongo
                ? "This query didn't trigger an indexing or structural recommendation. The built-in demo tables hold 5–49 rows, so most queries never produce a finding worth acting on — upload a schema to see the advisor work against realistic volume."
                : "This query didn't trigger an indexing, normalization, or structural recommendation."
            }
            action={
              schema?.builtIn && !isMongo && (
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
            Query rewrite
          </h3>
          {!comparison && (
            <Button onClick={runComparison} loading={loading}>Compare rewrite</Button>
          )}
        </div>

        <p className="page-note">
          {isMongo
            ? "Every rule here keeps the documents the query returns. The figures come from MongoDB's explain over both versions — counted while running each plan, so they are measured, not estimated."
            : "Every rule here preserves the rows the query returns. The row and cost figures come from EXPLAIN over both versions — they are the optimiser's estimates, not measurements."}
        </p>

        {compareError ? (
          <EmptyState title="Comparison failed" detail={compareError} />
        ) : !comparison && !loading ? (
          <EmptyState
            title="Not compared yet"
            detail={isMongo
              ? 'Click Compare rewrite to run the rewriter and explain both versions in MongoDB.'
              : 'Click Compare rewrite to run the rewriter and EXPLAIN both versions.'}
          />
        ) : comparison && !comparison.rewritten ? (
          <EmptyState title="No rewrite applied" detail={comparison.summary} />
        ) : comparison ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-6)' }}>
            <section>
              <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-2)' }}>Before / after</h4>
              <BeforeAfterDiff before={comparison.originalQuery} after={comparison.optimizedQuery} />
            </section>

            <section style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-6)' }}>
              <div>
                <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                  {isMongo ? 'Documents examined' : 'Estimated rows read'}
                </h4>
                {comparison.originalAnalysis?.estimatedRows != null
                  && comparison.optimizedAnalysis?.estimatedRows != null ? (
                  <CostDeltaChart
                    before={comparison.originalAnalysis.estimatedRows}
                    after={comparison.optimizedAnalysis.estimatedRows}
                    caption={isMongo
                      ? 'Documents examined, measured by MongoDB while running each plan with explain — not an estimate.'
                      : undefined}
                  />
                ) : (
                  <p className="page-note">
                    {isMongo
                      ? 'MongoDB reported no documents-examined figure for one of the two plans.'
                      : 'EXPLAIN reported no row estimate for one of the two plans.'}
                  </p>
                )}
              </div>
              <div>
                <h4 style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', marginBottom: 'var(--space-3)' }}>
                  Why it changed
                </h4>
                <RuleTraceList
                  trace={comparison.changes.map((c) => ({
                    ruleId: c.ruleId,
                    explanation: c.explanation,
                  }))}
                />
              </div>
            </section>

            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--ink-muted)', borderTop: '1px solid var(--border)', paddingTop: 'var(--space-4)' }}>
              {comparison.summary}
            </p>
          </div>
        ) : null}
      </section>
    </div>
  );
}
