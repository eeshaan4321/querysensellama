import { isLlmSource } from '../../api/contracts';
import './EngineBadge.css';

const LABEL = {
  RULE_ENGINE: 'Rule engine',
  OLLAMA: 'LLM fallback',
  GEMINI: 'LLM fallback',
  CACHE: 'Cache',
};

export function EngineBadge({ handledBy, size = 'md' }) {
  if (!handledBy) return null;
  const key = isLlmSource(handledBy) ? 'llm' : handledBy.toLowerCase().replace('_', '-');
  return (
    <span className={`engine-badge engine-badge--${key} engine-badge--${size}`}>
      <span className="engine-badge__dot" aria-hidden="true" />
      {LABEL[handledBy] || handledBy}
    </span>
  );
}
