import { AnimatePresence, motion } from 'framer-motion';
import { useEngineStatus } from '../../context/EngineStatusContext';
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
  const reduced = useReducedMotion();
  const tone = isLlmSource(status?.handledBy) ? 'llm' : 'rule';

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
    </header>
  );
}
