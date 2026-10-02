import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';
import { Button } from '../components/common/Button';
import { RecommendationList } from '../components/schema/RecommendationList';
import { adviseSchema } from '../api';
import { useWorkbench } from '../context/workbench.js';
import { useActiveSchema } from '../context/activeSchema.js';
import { TargetDb } from '../api/contracts';

export function SchemaAdvisorPage() {
  const { build, targetDb, resourceLevel, schemaAdvice, setSchemaAdvice } = useWorkbench();
  const { schemaFor } = useActiveSchema();
  const activeDb = build?.targetDb || targetDb;
  const isMongo = activeDb === TargetDb.MONGODB;
  const schema = schemaFor(activeDb);
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function runAdvisor() {
    setLoading(true);
    setError(null);
    try {
      const result = await adviseSchema({
        query: build.generatedQuery,
        targetDb: activeDb,
        resourceLevel,
      });
      setSchemaAdvice(result);
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Schema advisor failed.');
    } finally {
      setLoading(false);
    }
  }

  if (!build) {
    return (
      <div>
        <PageHeader title="Schema advisor" description="Indexing, normalization, and structural recommendations anchored to observed EXPLAIN behavior." />
        <EmptyState title="Nothing to advise on yet" detail="Build a query in the Workbench first." />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Schema advisor"
        description="Every recommendation is anchored to a specific EXPLAIN or Mongo finding — never a generic 'index everything' rule."
        action={!schemaAdvice && <Button onClick={runAdvisor} loading={loading}>Get recommendations</Button>}
      />

      {schema && !schema.builtIn && (
        <p className="page-note">
          {isMongo
            ? 'Anchored to MongoDB explain over your uploaded collections, which hold generated documents.'
            : 'Anchored to EXPLAIN over your uploaded schema, whose tables hold generated rows.'}
        </p>
      )}

      {error && <EmptyState title="Schema advisor failed" detail={error} />}

      {!schemaAdvice && !loading && !error && (
        <EmptyState title="No recommendations yet" detail="Click Get recommendations to run the Schema Advisor against this query." />
      )}

      {schemaAdvice && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
          {schemaAdvice.length === 0 ? (
            <EmptyState title="No issues found" detail="This query didn't trigger any indexing, normalization, or structural recommendation." />
          ) : (
            <RecommendationList recommendations={schemaAdvice} />
          )}
          <div>
            <Button variant="secondary" onClick={() => navigate('/optimization')}>
              Continue to optimization preview
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
