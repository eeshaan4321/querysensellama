import './RuleTracePanel.css';

const LAYER_NAME = {
  OLLAMA: 'the local language model',
  CACHE: 'a cached answer from an earlier identical question',
  RULE_ENGINE: 'the rule engine',
};

const KIND_LABEL = {
  SCHEMA_ALIAS: 'alias declared by the schema',
  SYNONYM: 'built-in synonym',
  TYPO: 'one letter off',
};

export function RuleTracePanel({ trace, handledBy, interpretations = [] }) {
  if (!trace) return null;

  const declined = trace.outcome === 'DECLINED';

  return (
    <section
      className={`rule-trace rule-trace--${declined ? 'declined' : 'built'}`}
      aria-label="How this query was built"
    >
      <header className="rule-trace__head">
        <span className="rule-trace__badge">
          {declined ? 'Rule engine declined' : 'Built by the rule engine'}
        </span>
        {!declined && trace.shape && (
          <span className="rule-trace__shape">{trace.shape}</span>
        )}
      </header>

      {declined ? (
        <>
          <p className="rule-trace__reason">{trace.declineReason}</p>
          <p className="rule-trace__handoff">
            Answered instead by {LAYER_NAME[handledBy] || 'another layer'}.
          </p>
        </>
      ) : (
        <>
          {interpretations.length > 0 && (
            <div className="rule-trace__read-as">
              <span className="rule-trace__read-as-label">Read as</span>
              <ul className="rule-trace__read-as-list">
                {interpretations.map((reading, i) => (
                  <li key={i}>
                    &ldquo;{reading.phrase}&rdquo;
                    <span aria-hidden="true"> → </span>
                    <code>{reading.item}</code>{' '}
                    <span className="rule-trace__kind">{KIND_LABEL[reading.kind] || reading.kind}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {trace.joinEdge && (
            <p className="rule-trace__join">
              <span className="rule-trace__join-edge">
                <code>
                  {trace.joinEdge.fromTable}.{trace.joinEdge.fromColumn}
                </code>
                <span aria-hidden="true"> → </span>
                <code>
                  {trace.joinEdge.toTable}.{trace.joinEdge.toColumn}
                </code>
              </span>
              <span className="rule-trace__origin">
                read from your schema&rsquo;s {trace.joinEdge.origin}, not inferred
              </span>
            </p>
          )}

          {trace.bindings?.length > 0 && (
            <dl className="rule-trace__bindings">
              {trace.bindings.map((binding, i) => (
                <div className="rule-trace__binding" key={i}>
                  <dt>&ldquo;{binding.phrase}&rdquo;</dt>
                  <dd>
                    <span className="rule-trace__kind">{binding.kind}</span>
                    <code>{binding.resolvedTo}</code>
                  </dd>
                </div>
              ))}
            </dl>
          )}

          {trace.checksPassed?.length > 0 && (
            <ul className="rule-trace__checks">
              {trace.checksPassed.map((check, i) => (
                <li key={i}>{check}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
