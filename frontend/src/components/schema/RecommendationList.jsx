import { CodeBlock } from '../common/CodeBlock';
import './RecommendationList.css';

const TYPE_META = {
  INDEX: { label: 'Index', tone: 'rule' },
  NORMALIZATION: { label: 'Normalization', tone: 'api' },
  STRUCTURAL: { label: 'Structural', tone: 'data' },
};

export function RecommendationList({ recommendations }) {
  return (
    <ul className="recommendation-list">
      {recommendations.map((r, i) => {
        const meta = TYPE_META[r.type] || TYPE_META.STRUCTURAL;
        const lead = r.summary || r.reason;
        const hasDetail = Boolean(r.summary && r.reason && r.reason !== r.summary);

        return (
          <li key={i} className={`recommendation-list__item recommendation-list__item--${meta.tone}`}>
            <div className="recommendation-list__head">
              <span className="recommendation-list__type">{meta.label}</span>
              {r.targetTable && (
                <span className="recommendation-list__target">
                  {r.targetTable}
                  {r.targetColumn ? `.${r.targetColumn}` : ''}
                </span>
              )}
            </div>

            <p className="recommendation-list__detail">{lead}</p>

            {hasDetail && (
              <details className="recommendation-list__technical">
                <summary className="recommendation-list__technical-toggle">Technical detail</summary>
                <p className="recommendation-list__technical-body">{r.reason}</p>
              </details>
            )}

            {r.suggestedAction && (
              <CodeBlock code={r.suggestedAction} dialect={r.suggestedAction.startsWith('db.') ? 'MONGODB' : 'SQL'} />
            )}
          </li>
        );
      })}
    </ul>
  );
}
