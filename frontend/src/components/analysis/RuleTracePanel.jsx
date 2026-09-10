import './RuleTracePanel.css';

/**
 * How the rule engine built this query — or why it declined and handed the
 * question on.
 *
 * WHY THIS IS ON THE ANALYSIS SCREEN AND NOT THE WORKBENCH:
 * the Workbench answers "is this the right query?". This answers "why is it
 * shaped this way?", which is the question a reader already has once they
 * are looking at the plan. It arrives via WorkbenchContext's `build`, so
 * /api/query/analyze is untouched and no extra request is made — the
 * pipeline stays at four calls.
 *
 * WHY DECLINES RENDER TOO:
 * `handledBy` says WHICH layer answered, never why the previous one passed.
 * The rule engine runs first on every request, so it has a reason either
 * way, and showing it is what makes the routing decision auditable rather
 * than asserted. This is the one part of the project's central claim that
 * was previously not demonstrable.
 *
 * WHY joinEdge.origin IS RENDERED VERBATIM:
 * "read from your schema's declared foreign key, not inferred" is the claim
 * a reviewer is most likely to doubt, and naming the exact columns lets them
 * check it against their own DDL in seconds. It is the one thing about a
 * generated join the language-model path cannot offer.
 */

const LAYER_NAME = {
  OLLAMA: 'the local language model',
  CACHE: 'a cached answer from an earlier identical question',
  RULE_ENGINE: 'the rule engine',
};

export function RuleTracePanel({ trace, handledBy }) {
  // Absent means there is no traced generation behind this analysis — a
  // history replay, or a query that did not come from the Workbench. It
  // does NOT mean the rules declined: that is outcome === 'DECLINED', and
  // it renders. Empty box avoided the same way VerdictBanner avoids it.
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
