import { isLlmSource } from '../../api/contracts';
import './ConfidenceMeter.css';

export function ConfidenceMeter({ value, handledBy }) {
  const pct = Math.round(value * 100);
  const tone = isLlmSource(handledBy) ? 'llm' : 'rule';
  return (
    <div className="confidence-meter" role="meter" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
      <div className="confidence-meter__track">
        <div
          className={`confidence-meter__fill confidence-meter__fill--${tone}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className="confidence-meter__value">{pct}%</span>
    </div>
  );
}
