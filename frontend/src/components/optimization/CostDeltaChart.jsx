import './CostDeltaChart.css';

export function CostDeltaChart({
  before,
  after,
  caption = 'Estimated rows examined, from EXPLAIN. This is an estimate — not a measured runtime.',
}) {
  const max = Math.max(before, after, 1);
  const rows = [
    { label: 'Before', value: before, tone: 'danger' },
    { label: 'After', value: after, tone: 'good' },
  ];

  return (
    <div className="cost-delta">
      {rows.map((row) => (
        <div className="cost-delta__row" key={row.label}>
          <span className="cost-delta__label">{row.label}</span>
          <div className="cost-delta__track">
            <div
              className={`cost-delta__bar cost-delta__bar--${row.tone}`}
              style={{ width: `${Math.max((row.value / max) * 100, 3)}%` }}
            />
          </div>
          <span className="cost-delta__value">{row.value}</span>
        </div>
      ))}
      <p className="cost-delta__caption">{caption}</p>
    </div>
  );
}
