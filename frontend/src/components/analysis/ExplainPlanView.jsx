import { motion } from 'framer-motion';
import { PlanMetrics } from './PlanMetrics';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import { useActiveSchema } from '../../context/activeSchema.js';
import { accessTypeGloss, FIELD_GLOSS } from '../../lib/explainGlossary';
import './ExplainPlanView.css';

const ROWS = [
  { key: 'accessType', label: 'Access type', gloss: accessTypeGloss },
  { key: 'keyUsed', label: 'Index used', gloss: () => FIELD_GLOSS.keyUsed },
  {
    key: 'hasFullTableScan',
    label: 'Full table scan',
    bool: true,
    badTone: true,
    gloss: (v) => (v ? FIELD_GLOSS.fullTableScan : null),
  },
  {
    key: 'usesFilesort',
    label: 'Uses filesort',
    bool: true,
    badTone: true,
    gloss: (v) => (v ? FIELD_GLOSS.usesFilesort : null),
  },
  {
    key: 'usesTemporaryTable',
    label: 'Temporary table',
    bool: true,
    badTone: true,
    gloss: (v) => (v ? FIELD_GLOSS.usesTemporaryTable : null),
  },
];

const TABLE_ROWS = [
  { key: 'accessType', label: 'Access type', gloss: accessTypeGloss },
  { key: 'keyUsed', label: 'Index used', gloss: () => FIELD_GLOSS.keyUsed },
  { key: 'possibleKeys', label: 'Indexes considered', gloss: () => FIELD_GLOSS.possibleKeys },
  { key: 'estimatedRows', label: 'Rows examined', number: true, gloss: () => FIELD_GLOSS.estimatedRows },
  {
    key: 'estimatedRowsReturned',
    label: 'Rows returned',
    number: true,
    gloss: () => FIELD_GLOSS.estimatedRowsReturned,
  },
  { key: 'ref', label: 'Joined on', gloss: () => FIELD_GLOSS.ref },
  {
    key: 'fullTableScan',
    label: 'Full table scan',
    bool: true,
    badTone: true,
    gloss: (v) => (v ? FIELD_GLOSS.fullTableScan : null),
  },
  {
    key: 'usesFilesort',
    label: 'Uses filesort',
    bool: true,
    badTone: true,
    gloss: (v) => (v ? FIELD_GLOSS.usesFilesort : null),
  },
  {
    key: 'usesTemporaryTable',
    label: 'Temporary table',
    bool: true,
    badTone: true,
    gloss: (v) => (v ? FIELD_GLOSS.usesTemporaryTable : null),
  },
];

function formatValue(value, row) {
  if (row.bool) return value ? 'Yes' : 'No';
  if (row.number && typeof value === 'number') return value.toLocaleString();
  return value ?? '—';
}

function valueClass(row, value, unavailable) {
  if (unavailable) return 'explain-plan__row-value explain-plan__row-value--unavailable';
  if (!row.bool) return 'explain-plan__row-value';
  return `explain-plan__row-value explain-plan__row-value--${value ? 'danger' : 'good'}`;
}

function MetricRows({ rows, source, unavailable, reduced }) {
  return (
    <ul className="explain-plan__rows">
      {rows.map((row, i) => {
        const value = source[row.key];
        const gloss = !unavailable && row.gloss ? row.gloss(value) : null;
        return (
          <motion.li
            key={row.key}
            className="explain-plan__row"
            initial={reduced ? false : { opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.2, delay: reduced ? 0 : i * 0.05, ease: [0.23, 1, 0.32, 1] }}
          >
            <div className="explain-plan__row-main">
              <span className="explain-plan__row-label">{row.label}</span>
              <span className={valueClass(row, value, unavailable)}>
                {unavailable ? 'Unavailable — EXPLAIN failed' : formatValue(value, row)}
              </span>
            </div>
            {gloss && <p className="explain-plan__row-gloss">{gloss}</p>}
          </motion.li>
        );
      })}
    </ul>
  );
}

export function ExplainPlanView({ plan }) {
  const reduced = useReducedMotion();
  const { schema } = useActiveSchema();
  const generated = Boolean(schema) && !schema.builtIn;
  const unavailable = Boolean(plan.explainFailed);
  const tables = Array.isArray(plan.tables) ? plan.tables : [];
  const perTable = !unavailable && tables.length > 0;

  return (
    <div className="explain-plan">
      {generated && !unavailable && (
        <p className="explain-plan__provenance">
          This plan is MySQL&apos;s, run against <code>{schema.catalog}</code>. Those tables were
          created from your uploaded schema and filled with generated rows — the access paths and
          index choices are real, the row counts describe synthetic data.
        </p>
      )}

      <PlanMetrics plan={plan} />

      {perTable ? (
        <div className="explain-plan__tables">
          {tables.length > 1 && (
            <p className="explain-plan__tables-intro">
              MySQL reads {tables.length} tables for this query. Each is planned separately, so each
              is reported separately here.
            </p>
          )}
          {tables.map((table, tableIndex) => (
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
          ))}
        </div>
      ) : (
        <MetricRows rows={ROWS} source={plan} unavailable={unavailable} reduced={reduced} />
      )}
    </div>
  );
}
