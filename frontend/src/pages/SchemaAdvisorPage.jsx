import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';
import { Button } from '../components/common/Button';
import { RecommendationList } from '../components/schema/RecommendationList';
import { adviseSchema } from '../api';
import { useWorkbench } from '../context/WorkbenchContext';

export function SchemaAdvisorPage() {
  const { build, targetDb, domain, resourceLevel, schemaAdvice, setSchemaAdvice } = useWorkbench();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function runAdvisor() {
    setLoading(true);
    setError(null);
    try {
      const result = await adviseSchema({
        query: build.generatedQuery,
        targetDb: build.targetDb || targetDb,
        domain,
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
