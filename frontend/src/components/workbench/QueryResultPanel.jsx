import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { CodeBlock } from '../common/CodeBlock';
import { EngineBadge } from '../common/EngineBadge';
import { ConfidenceMeter } from '../common/ConfidenceMeter';
import { Button } from '../common/Button';
import { SchemaWarning } from './SchemaWarning';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import { isLlmSource } from '../../api/contracts';
import './QueryResultPanel.css';

export function QueryResultPanel({ result, targetDb }) {
  const navigate = useNavigate();
  const reduced = useReducedMotion();
  const tone = isLlmSource(result.handledBy) ? 'llm' : 'rule';

  if (result.ruleTrace?.outcome === 'REFUSED') {
    return (
      <div className={`query-result query-result--${tone}`}>
        <div className="query-result__head">
          <span className="query-result__heading">No query generated</span>
          <EngineBadge handledBy={result.handledBy} />
        </div>
        <div className="schema-warning" role="status">
          <span className="schema-warning__label">Refused by the rule engine</span>
          <p className="schema-warning__detail">{result.ruleTrace.declineReason}</p>
          <p className="schema-warning__note">
            The language model was not asked either, and nothing was saved to history.
          </p>
        </div>
      </div>
    );
  }

  return (
    <motion.div
      key={result.handledBy}
      className={`query-result query-result--${tone}`}
      initial={reduced ? false : { opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.32, ease: [0.23, 1, 0.32, 1] }}
    >
      <div className="query-result__head">
        <span className="query-result__heading">Generated query</span>
        <EngineBadge handledBy={result.handledBy} />
      </div>

      <CodeBlock code={result.generatedQuery} dialect={targetDb} />

      <SchemaWarning warning={result.schemaWarning} />

      <div className="query-result__foot">
        <ConfidenceMeter value={result.confidence} handledBy={result.handledBy} />
        <Button variant="secondary" onClick={() => navigate('/analysis')}>
          Analyze this query
        </Button>
      </div>
    </motion.div>
  );
}
