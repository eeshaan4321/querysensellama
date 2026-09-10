import { motion } from 'framer-motion';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import './PipelineDiagram.css';

const BACKBONE = [
  { label: 'Natural language input', sub: 'Workbench', tone: 'api' },
  { label: 'Rule-based query builder', sub: 'RuleBasedQueryBuilder', tone: 'rule' },
  { label: 'Query analysis engine', sub: 'EXPLAIN + JSQL Parser', tone: 'rule' },
  { label: 'Domain + resource optimizer', sub: 'domain and resource rules', tone: 'rule' },
  { label: 'Schema advisor', sub: 'index / normalization', tone: 'rule' },
  { label: 'Comparison engine', sub: 'before/after + reasoning', tone: 'rule' },
  { label: 'History', sub: 'JDBC/HikariCP + MongoDB client', tone: 'data' },
];

/**
 * A presentation aid, not just an app screen — reuses the same five-color
 * key as the team's other diagrams so this reads as the same system the
 * panel has already seen. A numbered-step grid (not a fixed-width arrow
 * chain) so it stays legible at projector resolutions instead of forcing
 * horizontal scrolling on a shared screen. The rule-engine backbone stays
 * teal end to end; the LLM fallback is called out separately because it
 * rejoins that same pipeline at step 3 rather than running in parallel to it.
 */
export function PipelineDiagram() {
  const reduced = useReducedMotion();

  return (
    <div className="pipeline">
      <ol className="pipeline__backbone">
        {BACKBONE.map((node, i) => (
          <motion.li
            key={node.label}
            className={`pipeline__node pipeline__node--${node.tone}`}
            initial={reduced ? false : { opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25, delay: reduced ? 0 : i * 0.05, ease: [0.23, 1, 0.32, 1] }}
          >
            <span className="pipeline__node-index">{i + 1}</span>
            <span className="pipeline__node-label">{node.label}</span>
            <span className="pipeline__node-sub">{node.sub}</span>
          </motion.li>
        ))}
      </ol>

      <motion.div
        className="pipeline__node pipeline__node--llm pipeline__branch-node"
        initial={reduced ? false : { opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25, delay: reduced ? 0 : 0.4, ease: [0.23, 1, 0.32, 1] }}
      >
        <span className="pipeline__node-index pipeline__node-index--llm">2b</span>
        <span className="pipeline__node-label">LLM fallback</span>
        <span className="pipeline__node-sub">Ollama · qwen2.5-coder:7b — low-confidence cases</span>
        <span className="pipeline__branch-caption">
          Only reached when step 2's confidence is low. Rejoins the pipeline at step 3 — same
          downstream analysis either way.
        </span>
      </motion.div>

      <div className="pipeline__legend">
        <LegendItem tone="rule" label="Rule-based engine" />
        <LegendItem tone="llm" label="LLM fallback" />
        <LegendItem tone="api" label="Frontend / API" />
        <LegendItem tone="data" label="Data access" />
      </div>
    </div>
  );
}

function LegendItem({ tone, label }) {
  return (
    <span className="pipeline__legend-item">
      <span className={`pipeline__legend-dot pipeline__legend-dot--${tone}`} />
      {label}
    </span>
  );
}
