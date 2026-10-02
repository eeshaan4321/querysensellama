import { motion } from 'framer-motion';
import { PlanMetrics } from './PlanMetrics';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import { useActiveSchema } from '../../context/activeSchema.js';
import { isMongoAnalysis, TargetDb } from '../../api/contracts';
import {
  accessTypeGloss, FIELD_GLOSS, mongoAccessTypeGloss, MONGO_FIELD_GLOSS,
} from '../../lib/explainGlossary';
import './ExplainPlanView.css';

function flagRows(isMongo, gloss, scanKey) {
  return [
    {
      key: scanKey,
      label: isMongo ? 'Collection scan' : 'Full table scan',
      bool: true,
      badTone: true,
      gloss: (v) => (v ? gloss.fullTableScan : null),
    },
    {
      key: 'usesFilesort',
      label: isMongo ? 'In-memory sort' : 'Uses filesort',
      bool: true,
      badTone: true,
      gloss: (v) => (v ? gloss.usesFilesort : null),
    },
    {
      key: 'usesTemporaryTable',
      label: isMongo ? 'Grouping spilled to disk' : 'Temporary table',
      bool: true,
      badTone: true,
      gloss: (v) => (v ? gloss.usesTemporaryTable : null),
    },
  ];
}

function summaryRows(isMongo) {
  const gloss = isMongo ? MONGO_FIELD_GLOSS : FIELD_GLOSS;
  return [
    { key: 'accessType', label: isMongo ? 'Plan stage' : 'Access type', gloss: isMongo ? mongoAccessTypeGloss : accessTypeGloss },
    { key: 'keyUsed', label: 'Index used', gloss: () => gloss.keyUsed },
    ...flagRows(isMongo, gloss, 'hasFullTableScan'),
  ];
}

function tableRows(isMongo) {
  const gloss = isMongo ? MONGO_FIELD_GLOSS : FIELD_GLOSS;
  const unit = isMongo ? 'Documents' : 'Rows';
  return [
    { key: 'accessType', label: isMongo ? 'Plan stage' : 'Access type', gloss: isMongo ? mongoAccessTypeGloss : accessTypeGloss },
    { key: 'keyUsed', label: 'Index used', gloss: () => gloss.keyUsed },
    { key: 'possibleKeys', label: 'Indexes considered', gloss: () => gloss.possibleKeys },
    { key: 'estimatedRows', label: `${unit} examined`, number: true, gloss: () => gloss.estimatedRows },
    { key: 'estimatedRowsReturned', label: `${unit} returned`, number: true, gloss: () => gloss.estimatedRowsReturned },
    ...(isMongo ? [] : [{ key: 'ref', label: 'Joined on', gloss: () => gloss.ref }]),
    ...flagRows(isMongo, gloss, 'fullTableScan'),
  ];
}

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

function MetricRows({ rows, source, unavailable, unavailableText, reduced }) {
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
                {unavailable ? unavailableText : formatValue(value, row)}
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
  const isMongo = isMongoAnalysis(plan);
  const { schemaFor } = useActiveSchema();
  const schema = schemaFor(isMongo ? TargetDb.MONGODB : TargetDb.SQL);
  const generated = Boolean(schema) && !schema.builtIn;
  const unavailable = Boolean(plan.explainFailed);
  const unavailableText = isMongo ? 'Unavailable — explain failed' : 'Unavailable — EXPLAIN failed';
  const tables = Array.isArray(plan.tables) ? plan.tables : [];
  const perTable = !unavailable && tables.length > 0;

  return (
    <div className="explain-plan">
      {generated && !unavailable && (
        <p className="explain-plan__provenance">
          {isMongo ? (
            <>
              This plan is MongoDB&apos;s, run against <code>{schema.catalog}</code>. Those collections were
              created from your uploaded schema and filled with generated documents — the plan stages and
              index choices are real, the document counts describe synthetic data.
            </>
          ) : (
            <>
              This plan is MySQL&apos;s, run against <code>{schema.catalog}</code>. Those tables were
              created from your uploaded schema and filled with generated rows — the access paths and
              index choices are real, the row counts describe synthetic data.
            </>
          )}
        </p>
      )}

      <PlanMetrics plan={plan} />

      {perTable ? (
        <div className="explain-plan__tables">
          {tables.length > 1 && (
            <p className="explain-plan__tables-intro">
              {isMongo
                ? `MongoDB reads ${tables.length} collections for this query. Each is reported separately here.`
                : `MySQL reads ${tables.length} tables for this query. Each is planned separately, so each is reported separately here.`}
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
                  {table.fullTableScan
                    ? isMongo ? 'Collection scan' : 'Full scan'
                    : `Index: ${table.keyUsed || 'yes'}`}
                </span>
              </header>
              {table.extra && <p className="explain-plan__table-extra">{table.extra}</p>}
              <MetricRows
                rows={tableRows(isMongo)}
                source={table}
                unavailable={false}
                unavailableText={unavailableText}
                reduced={reduced}
              />
            </section>
          ))}
        </div>
      ) : (
        <MetricRows
          rows={summaryRows(isMongo)}
          source={plan}
          unavailable={unavailable}
          unavailableText={unavailableText}
          reduced={reduced}
        />
      )}
    </div>
  );
}
