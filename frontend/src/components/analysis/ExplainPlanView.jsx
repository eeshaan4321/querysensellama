import { motion } from 'framer-motion';
import { StatTile } from '../common/StatTile';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import './ExplainPlanView.css';

/**
 * The collapsed, whole-query fields. Accurate when EXPLAIN reported a single
 * table — meaningless the moment there are two, see the note on TABLE_ROWS.
 */
const ROWS = [
  { key: 'accessType', label: 'Access type' },
  { key: 'keyUsed', label: 'Index used' },
  { key: 'hasFullTableScan', label: 'Full table scan', bool: true, badTone: true },
  { key: 'usesFilesort', label: 'Uses filesort', bool: true, badTone: true },
  { key: 'usesTemporaryTable', label: 'Temporary table', bool: true, badTone: true },
];

/**
 * The same metrics as ROWS but read off one entry of `analysis.tables`, whose
 * field names differ from the collapsed ones (`fullTableScan`, not
 * `hasFullTableScan`) because they map a single EXPLAIN row rather than a
 * summary of all of them.
 */
const TABLE_ROWS = [
  { key: 'accessType', label: 'Access type' },
  { key: 'keyUsed', label: 'Index used' },
  { key: 'estimatedRows', label: 'Rows examined' },
  { key: 'ref', label: 'Joined on' },
  { key: 'fullTableScan', label: 'Full table scan', bool: true, badTone: true },
  { key: 'usesFilesort', label: 'Uses filesort', bool: true, badTone: true },
  { key: 'usesTemporaryTable', label: 'Temporary table', bool: true, badTone: true },
];

function formatValue(value, isBool) {
  if (isBool) return value ? 'Yes' : 'No';
  return value ?? '—';
}

function valueClass(row, value, unavailable) {
  if (unavailable) return 'explain-plan__row-value explain-plan__row-value--unavailable';
  if (!row.bool) return 'explain-plan__row-value';
  return `explain-plan__row-value explain-plan__row-value--${value ? 'danger' : 'good'}`;
}

/**
 * One EXPLAIN metric list. Shared by the single-table view and by each table
 * of the per-table view so the two can't drift apart visually.
 */
function MetricRows({ rows, source, unavailable, reduced }) {
  return (
    <ul className="explain-plan__rows">
      {rows.map((row, i) => {
        const value = source[row.key];
        return (
          <motion.li
            key={row.key}
            className="explain-plan__row"
            initial={reduced ? false : { opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.2, delay: reduced ? 0 : i * 0.05, ease: [0.23, 1, 0.32, 1] }}
          >
            <span className="explain-plan__row-label">{row.label}</span>
            <span className={valueClass(row, value, unavailable)}>
              {unavailable ? 'Unavailable — EXPLAIN failed' : formatValue(value, row.bool)}
            </span>
          </motion.li>
        );
      })}
    </ul>
  );
}

/**
 * MySQL's EXPLAIN emits one row per table. For a single-table query the
 * backend's collapsed summary fields describe it exactly, so the honest form
 * is a status-annotated metric list plus one headline stat — not a fabricated
 * plan tree, since this analyzer inspects table accesses, not a node graph.
 *
 * WHY THE PER-TABLE BRANCH EXISTS:
 * For a join, those same collapsed fields merge rows that describe *different
 * tables* — the backend keeps the largest row estimate and ORs the booleans.
 * On `customers c LEFT JOIN orders o` that produced `accessType: ALL` (from
 * `c`) alongside `Index used: customer_id` (from `o`), which reads as one
 * table both scanning fully and using an index. The backend already returns
 * the un-collapsed rows in `analysis.tables`; this component simply stopped
 * short of rendering them. When there is more than one, show them separately
 * and let the summary stat carry the whole-query headline.
 *
 * `tables` is absent in mock mode and on Mongo results, so its absence falls
 * back to the collapsed view rather than rendering nothing.
 *
 * `explainFailed` distinguishes "EXPLAIN ran and these fields are genuinely
 * empty/false" from "EXPLAIN never successfully ran, so these fields carry
 * no signal at all" — without it, both cases rendered as the same bare "—",
 * which reads as "no scan issues" rather than "we don't know." The specific
 * reason still lives in the warnings list rendered above this component.
 */
export function ExplainPlanView({ plan }) {
  const reduced = useReducedMotion();
  const unavailable = Boolean(plan.explainFailed);
  const tables = Array.isArray(plan.tables) ? plan.tables : [];
  const perTable = !unavailable && tables.length > 1;

  return (
    <div className="explain-plan">
      <StatTile
        label={perTable ? 'Most rows examined at one table' : 'Estimated rows examined'}
        value={unavailable ? 'Unavailable' : (plan.estimatedRows ?? '—')}
        deltaTone={unavailable ? 'neutral' : plan.hasFullTableScan ? 'danger' : 'good'}
        caption={
          unavailable
            ? 'EXPLAIN could not run for this query — see the warning above.'
            : perTable
              ? `${tables.length} tables in this plan — each is reported separately below.`
              : plan.hasFullTableScan
                ? 'No index narrows this scan.'
                : `Narrowed via ${plan.keyUsed || 'an index'}.`
        }
      />

      {perTable ? (
        tables.map((table, tableIndex) => (
          <section
            className="explain-plan__table"
            key={`${table.tableName ?? 'table'}-${tableIndex}`}
          >
            <header className="explain-plan__table-header">
              <span className="explain-plan__table-name">{table.tableName ?? '—'}</span>
              <span
                className={`explain-plan__table-badge explain-plan__table-badge--${
                  table.fullTableScan ? 'danger' : 'good'
                }`}
              >
                {table.fullTableScan ? 'Full scan' : `Index: ${table.keyUsed || 'yes'}`}
              </span>
            </header>
            {table.extra && <p className="explain-plan__table-extra">{table.extra}</p>}
            <MetricRows rows={TABLE_ROWS} source={table} unavailable={false} reduced={reduced} />
          </section>
        ))
      ) : (
        <MetricRows rows={ROWS} source={plan} unavailable={unavailable} reduced={reduced} />
      )}
    </div>
  );
}
