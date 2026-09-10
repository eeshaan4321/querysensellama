import './SuggestionsList.css';

const SEVERITY_TONE = { INFO: 'info', WARNING: 'warning', CRITICAL: 'critical' };
const SOURCE_LABEL = { DOMAIN: 'Domain', RESOURCE: 'Resource' };

/** Renders the real Domain-Sensitive Optimization + Resource-Conscious Decision suggestions returned by /analyze. */
export function SuggestionsList({ suggestions }) {
  if (!suggestions?.length) {
    return <p className="suggestions-list__clear">No domain or resource suggestions for this query.</p>;
  }

  return (
    <ul className="suggestions-list">
      {suggestions.map((s, i) => (
        <li key={i} className={`suggestions-list__item suggestions-list__item--${SEVERITY_TONE[s.severity] || 'info'}`}>
          <span className="suggestions-list__dot" aria-hidden="true" />
          <div>
            <div className="suggestions-list__meta">
              <span className="suggestions-list__source">{SOURCE_LABEL[s.source] || s.source}</span>
              <span className="suggestions-list__severity">{s.severity}</span>
            </div>
            <p className="suggestions-list__message">{s.message}</p>
          </div>
        </li>
      ))}
    </ul>
  );
}
