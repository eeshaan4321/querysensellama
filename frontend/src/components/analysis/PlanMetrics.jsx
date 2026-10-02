import { FIELD_GLOSS, MONGO_FIELD_GLOSS, MONGO_PLAN_FOOTNOTE, QUERY_COST_CAVEAT } from '../../lib/explainGlossary';
import { isMongoAnalysis } from '../../api/contracts';
import './PlanMetrics.css';

function count(value) {
  return typeof value === 'number' ? value.toLocaleString() : null;
}

function amplification(examined, returned) {
  if (typeof examined !== 'number' || typeof returned !== 'number') return null;
  if (returned === 0) return `over ${examined.toLocaleString()}×`;
  const ratio = examined / returned;
  if (ratio < 1.05) return 'none';
  return `${ratio >= 10 ? Math.round(ratio).toLocaleString() : ratio.toFixed(1)}×`;
}

function Metric({ label, value, gloss, tone }) {
  if (value === null || value === undefined) return null;
  return (
    <div className={`plan-metric${tone ? ` plan-metric--${tone}` : ''}`}>
      <span className="plan-metric__label">{label}</span>
      <span className="plan-metric__value">{value}</span>
      <span className="plan-metric__gloss">{gloss}</span>
    </div>
  );
}

export function PlanMetrics({ plan }) {
  if (plan.explainFailed) return null;

  const isMongo = isMongoAnalysis(plan);
  const gloss = isMongo ? MONGO_FIELD_GLOSS : FIELD_GLOSS;
  const unit = isMongo ? 'Documents' : 'Rows';
  const examined = plan.estimatedRows;
  const returned = plan.estimatedRowsReturned;
  const waste = isMongo && plan.filtered == null ? null : amplification(examined, returned);

  const tiles = [
    { key: 'examined', label: `${unit} examined`, value: count(examined), gloss: gloss.estimatedRows },
    { key: 'returned', label: `${unit} returned`, value: count(returned), gloss: gloss.estimatedRowsReturned },
    {
      key: 'waste',
      label: 'Wasted work',
      value: waste,
      tone: waste && waste !== 'none' ? 'danger' : undefined,
      gloss:
        waste === 'none'
          ? isMongo
            ? 'Nearly every document read is a document returned — almost nothing is discarded.'
            : 'Nearly every row read is a row returned — almost nothing is discarded.'
          : isMongo
            ? 'Documents read for each document returned. The rest are read and thrown away.'
            : 'Rows read for each row returned. The rest are read and thrown away.',
    },
    isMongo
      ? {
          key: 'time',
          label: 'Execution time',
          value: typeof plan.executionMillis === 'number' ? `${plan.executionMillis.toLocaleString()} ms` : null,
          gloss: MONGO_FIELD_GLOSS.executionMillis,
        }
      : {
          key: 'cost',
          label: 'Query cost',
          value: typeof plan.queryCost === 'number' ? plan.queryCost.toLocaleString() : null,
          gloss: QUERY_COST_CAVEAT,
        },
  ].filter((tile) => tile.value !== null && tile.value !== undefined);

  if (tiles.length === 0) return null;

  return (
    <section className="plan-metrics" aria-label="Plan metrics">
      <div className="plan-metrics__grid">
        {tiles.map((tile) => (
          <Metric key={tile.key} label={tile.label} value={tile.value} gloss={tile.gloss} tone={tile.tone} />
        ))}
      </div>
      <p className="plan-metrics__footnote">
        {isMongo
          ? MONGO_PLAN_FOOTNOTE
          : "Every figure here is MySQL's own estimate, read from its execution plan. QuerySense does not run your query, so none of these is a measured result."}
      </p>
    </section>
  );
}
