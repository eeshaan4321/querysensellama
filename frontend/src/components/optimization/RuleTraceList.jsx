import './RuleTraceList.css';

export function RuleTraceList({ trace }) {
  return (
    <ol className="rule-trace">
      {trace.map((step, i) => (
        <li key={step.ruleId} className="rule-trace__item">
          <span className="rule-trace__index">{i + 1}</span>
          <div>
            <div className="rule-trace__id">{step.ruleId}</div>
            <div className="rule-trace__explanation">{step.explanation}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}
