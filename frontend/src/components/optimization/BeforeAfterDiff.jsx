import { motion } from 'framer-motion';
import { diffLines } from '../../lib/diff';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import './BeforeAfterDiff.css';

export function BeforeAfterDiff({ before, after }) {
  const lines = diffLines(before, after);
  const reduced = useReducedMotion();

  return (
    <pre className="before-after-diff">
      <code>
        {lines.map((line, i) => (
          <motion.div
            key={i}
            className={`before-after-diff__line before-after-diff__line--${line.type}`}
            initial={reduced ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.18, delay: reduced ? 0 : i * 0.03 }}
          >
            <span className="before-after-diff__gutter">{line.type === 'removed' ? '−' : line.type === 'added' ? '+' : ' '}</span>
            {line.text || ' '}
          </motion.div>
        ))}
      </code>
    </pre>
  );
}
