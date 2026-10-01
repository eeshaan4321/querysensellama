import { useState } from 'react';
import { PageHeader } from '../components/common/PageHeader';
import { SchemaSourcePanel } from '../components/schemaSource/SchemaSourcePanel';
import { SchemaTablePreview } from '../components/schemaSource/SchemaTablePreview';
import { TargetDb } from '../api/contracts';
import { useActiveSchema } from '../context/activeSchema.js';
import { useWorkbench } from '../context/workbench.js';

const DATABASES = [
  { value: TargetDb.SQL, label: 'MySQL' },
  { value: TargetDb.MONGODB, label: 'MongoDB' },
];

export function SchemaSourcePage() {
  const { targetDb } = useWorkbench();
  const [dialect, setDialect] = useState(targetDb);
  const { schemaFor, loading, error } = useActiveSchema();

  return (
    <div>
      <PageHeader
        title="Schema source"
        description="Upload your own schema for MySQL or MongoDB and QuerySense works against it — the rule engine, the LLM prompt and the plan analysis all follow it. Each database keeps its own active schema, and the built-in demos stay where they are."
      />

      <div className="schema-switch" role="tablist" aria-label="Database">
        {DATABASES.map((db) => (
          <button
            key={db.value}
            type="button"
            role="tab"
            aria-selected={dialect === db.value}
            className={`schema-switch__option${dialect === db.value ? ' schema-switch__option--active' : ''}`}
            onClick={() => setDialect(db.value)}
          >
            {db.label}
          </button>
        ))}
      </div>

      <SchemaSourcePanel key={dialect} dialect={dialect} />

      {loading && <p className="page-note">Reading the active schema…</p>}
      {error && (
        <p className="page-note" role="alert">
          {error}
        </p>
      )}

      <SchemaTablePreview schema={schemaFor(dialect)} />
    </div>
  );
}
