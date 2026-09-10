import { AnimatePresence, motion } from 'framer-motion';
import { useEngineStatus } from '../../context/EngineStatusContext';
import { useActiveSchema } from '../../context/ActiveSchemaContext';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import { isLlmSource } from '../../api/contracts';
import './EngineStatusBar.css';

const COPY = {
  RULE_ENGINE: 'Rule engine handled the last query',
  OLLAMA: 'LLM fallback handled the last query',
  GEMINI: 'LLM fallback handled the last query', // legacy — see contracts.js
  CACHE: 'Served from cache',
};

/**
 * Persistent header that makes "which path handled this" visible everywhere,
 * not just on the Workbench. The tone (teal/amber) is the same mode-shift
 * signal used on the Workbench result panel, so switching pages never loses
 * that context.
 */
export function EngineStatusBar() {
  const { status } = useEngineStatus();
  const { schema } = useActiveSchema();
  const reduced = useReducedMotion();
  const tone = isLlmSource(status?.handledBy) ? 'llm' : 'rule';
  const uploaded = Boolean(schema) && !schema.builtIn;

  return (
    <header className="engine-status-bar">
      <AnimatePresence mode="wait">
        <motion.div
          key={status ? `${status.handledBy}-${status.at}` : 'idle'}
          className={`engine-status-bar__pill engine-status-bar__pill--${status ? tone : 'idle'}`}
          initial={reduced ? false : { opacity: 0, y: -4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.22, ease: [0.23, 1, 0.32, 1] }}
        >
          <span className="engine-status-bar__dot" aria-hidden="true" />
          <span>
            {status
              ? `${COPY[status.handledBy] || status.handledBy}${
                  status.confidence != null ? ` (${Math.round(status.confidence * 100)}% confidence)` : ''
                }`
              : 'No query run yet this session'}
          </span>
        </motion.div>
      </AnimatePresence>

      {/*
        Which schema is live is the same class of fact as which engine
        answered: provenance the user needs in order to trust anything on
        screen. An uploaded schema says "generated data" here because its
        row estimates are real EXPLAIN output over synthetic rows, and no
        screen should let that pass for a measurement.
      */}
      <div
        className={`engine-status-bar__schema engine-status-bar__schema--${uploaded ? 'uploaded' : 'builtin'}`}
        title={
          uploaded
            ? `Uploaded schema, provisioned into ${schema.catalog}. Its tables hold generated rows.`
            : 'The built-in demo schema, in the connected database.'
        }
      >
        <span className="engine-status-bar__dot" aria-hidden="true" />
        <span>
          {!schema
            ? 'Schema unknown'
            : schema.builtIn
              ? 'Built-in demo schema'
              : `${schema.label} · ${schema.tables.length} tables · generated data`}
        </span>
      </div>
    </header>
  );
}
