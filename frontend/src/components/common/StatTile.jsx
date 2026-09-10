import './StatTile.css';

/** A single labeled metric, optionally with a delta. Used for cost/row estimates — never a bare chart when one number is the actual answer. */
export function StatTile({ label, value, delta, deltaTone = 'good', caption }) {
  return (
    <div className="stat-tile">
      <span className="stat-tile__label">{label}</span>
      <span className="stat-tile__value">{value}</span>
      {delta && <span className={`stat-tile__delta stat-tile__delta--${deltaTone}`}>{delta}</span>}
      {caption && <span className="stat-tile__caption">{caption}</span>}
    </div>
  );
}
