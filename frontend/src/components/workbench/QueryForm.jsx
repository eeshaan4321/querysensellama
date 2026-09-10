import { Select } from '../common/Select';
import { Button } from '../common/Button';
import { Domain, ResourceLevel, TargetDb } from '../../api/contracts';
import { RESOURCE_LEVEL_INFO } from '../../lib/resourceLevels';
import './QueryForm.css';
import { useActiveSchema } from '../../context/ActiveSchemaContext';

const DOMAIN_OPTIONS = [
  { value: Domain.ECOMMERCE, label: 'E-commerce' },
  { value: Domain.ANALYTICAL, label: 'Analytical' },
  { value: Domain.TRANSACTIONAL, label: 'Transactional' },
  { value: Domain.OTHER, label: 'Other' },
];

const RESOURCE_OPTIONS = [
  { value: ResourceLevel.LOW, label: 'Low' },
  { value: ResourceLevel.MEDIUM, label: 'Medium' },
  { value: ResourceLevel.HIGH, label: 'High' },
];

const DB_OPTIONS = [
  { value: TargetDb.SQL, label: 'SQL (MySQL)' },
  { value: TargetDb.MONGODB, label: 'MongoDB' },
];

// A simple case the rule engine (not the LLM) handles directly, so the demo
// shows the fast path by default.
const BUILT_IN_PLACEHOLDER = 'show customers from Mumbai';

// Once a schema is uploaded, `customers` may not exist any more. A
// placeholder naming a table the engine would decline is a small thing
// that makes the whole feature look unfinished, so it follows the active
// schema's first table instead.
function placeholderFor(schema) {
  if (!schema || schema.builtIn || !schema.tables.length) return BUILT_IN_PLACEHOLDER;
  return `show all ${schema.tables[0].name}`;
}

export function QueryForm({
  domain, setDomain, resourceLevel, setResourceLevel, targetDb, setTargetDb,
  text, setText, onSubmit, loading,
}) {
  const { schema } = useActiveSchema();
  const resourceInfo = RESOURCE_LEVEL_INFO[resourceLevel];
  const placeholder = placeholderFor(schema);

  function handleSubmit(e) {
    e.preventDefault();
    if (!text.trim()) return;
    onSubmit(text.trim());
  }

  return (
    <form className="query-form" onSubmit={handleSubmit}>
      <label className="query-form__field">
        <span className="query-form__label">Describe what you want in plain language</span>
        <textarea
          className="query-form__textarea"
          name="naturalLanguageQuery"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={placeholder}
          rows={3}
        />
      </label>

      <div className="query-form__row">
        <Select label="Domain" value={domain} onChange={setDomain} options={DOMAIN_OPTIONS} />
        <Select label="Resource level" value={resourceLevel} onChange={setResourceLevel} options={RESOURCE_OPTIONS} />
        <Select label="Target database" value={targetDb} onChange={setTargetDb} options={DB_OPTIONS} />
        <Button type="submit" loading={loading} disabled={!text.trim()}>
          Run query
        </Button>
      </div>

      {resourceInfo && (
        <p className="query-form__hint">
          <strong>{resourceInfo.label}</strong> ({resourceInfo.spec}) — {resourceInfo.description}
        </p>
      )}
    </form>
  );
}
