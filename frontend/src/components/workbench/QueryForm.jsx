import { Select } from '../common/Select';
import { Button } from '../common/Button';
import { Domain, ResourceLevel, TargetDb } from '../../api/contracts';
import { RESOURCE_LEVEL_INFO } from '../../lib/resourceLevels';
import './QueryForm.css';

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
const PLACEHOLDER = 'show customers from Mumbai';

export function QueryForm({
  domain, setDomain, resourceLevel, setResourceLevel, targetDb, setTargetDb,
  text, setText, onSubmit, loading,
}) {
  const resourceInfo = RESOURCE_LEVEL_INFO[resourceLevel];

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
          placeholder={PLACEHOLDER}
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
