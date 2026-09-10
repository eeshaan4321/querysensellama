import './CostDeltaChart.css';

/**
 * Two-bar before/after comparison. Colored by state (danger -> good), not
 * by arbitrary series hue — this is a status encoding, always paired with
 * a direct value label so color is never the only signal. Framed with an
 * explicit "estimate" caveat per the literature (Kim et al. 2022): never
 * present a cost estimate as a guaranteed result.
 */
export function CostDeltaChart({ before, after }) {
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
      <p className="cost-delta__caption">
        Estimated rows examined, from EXPLAIN. This is an estimate — not a measured runtime.
      </p>
    </div>
  );
}
