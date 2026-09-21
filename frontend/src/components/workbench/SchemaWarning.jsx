import './SchemaWarning.css';

/** Warns that the model's SQL names something the active schema does not have. */
export function SchemaWarning({ warning }) {
  if (!warning) return null;

  return (
    <div className="schema-warning" role="status">
      <span className="schema-warning__label">Does not match your schema</span>
      <p className="schema-warning__detail">{warning}</p>
      <p className="schema-warning__note">
        The query is shown as the model wrote it, but it has not been saved — so it cannot be
        served back later as a cached answer.
      </p>
    </div>
  );
}
