import './StatTile.css';

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
