import { motion } from 'framer-motion';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import './VerdictBanner.css';

const TONE = {
  CRITICAL: 'critical',
  WARNING: 'warning',
  INFO: 'good',
  UNKNOWN: 'unknown',
};

const ICON = {
  CRITICAL: '!',
  WARNING: '!',
  INFO: '✓',
  UNKNOWN: '?',
};

export function VerdictBanner({ verdict }) {
  const reduced = useReducedMotion();

  if (!verdict) return null;

  const tone = TONE[verdict.severity] || 'unknown';

  return (
    <motion.section
      className={`verdict verdict--${tone}`}
      initial={reduced ? false : { opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: [0.23, 1, 0.32, 1] }}
      aria-label="Analysis verdict"
    >
      <div className="verdict__top">
        <span className="verdict__icon" aria-hidden="true">
          {ICON[verdict.severity] || '?'}
        </span>
        <span className="verdict__label">{verdict.label}</span>
      </div>

      <p className="verdict__headline">{verdict.headline}</p>

      {verdict.detail && <p className="verdict__detail">{verdict.detail}</p>}
    </motion.section>
  );
}
