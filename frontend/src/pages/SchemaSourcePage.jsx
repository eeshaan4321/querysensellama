import { PageHeader } from '../components/common/PageHeader';
import { SchemaSourcePanel } from '../components/schemaSource/SchemaSourcePanel';
import { SchemaTablePreview } from '../components/schemaSource/SchemaTablePreview';
import { useActiveSchema } from '../context/ActiveSchemaContext';

export function SchemaSourcePage() {
  const { schema, loading, error } = useActiveSchema();

  return (
    <div>
      <PageHeader
        title="Schema source"
        description="Upload your own schema and QuerySense works against it — the rule engine, the LLM prompt and EXPLAIN all follow it. The built-in demo schema stays where it is."
      />

      <SchemaSourcePanel />

      {loading && <p className="page-note">Reading the active schema…</p>}
      {error && (
        <p className="page-note" role="alert">
          {error}
        </p>
      )}

      <SchemaTablePreview schema={schema} />
    </div>
  );
}
