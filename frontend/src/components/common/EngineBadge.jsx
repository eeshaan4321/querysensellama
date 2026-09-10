import { isLlmSource } from '../../api/contracts';
import './EngineBadge.css';

const LABEL = {
  RULE_ENGINE: 'Rule engine',
  OLLAMA: 'LLM fallback',
  GEMINI: 'LLM fallback', // legacy history rows — see contracts.js
  CACHE: 'Cache',
};

/** Reusable "which path handled this" pill. Same visual language wherever it appears. */
export function EngineBadge({ handledBy, size = 'md' }) {
  if (!handledBy) return null;
  // Both LLM values share one modifier so the amber treatment survives the
  // provider rename and applies to old GEMINI rows too.
  const key = isLlmSource(handledBy) ? 'llm' : handledBy.toLowerCase().replace('_', '-');
  return (
    <span className={`engine-badge engine-badge--${key} engine-badge--${size}`}>
      <span className="engine-badge__dot" aria-hidden="true" />
      {LABEL[handledBy] || handledBy}
    </span>
  );
}
