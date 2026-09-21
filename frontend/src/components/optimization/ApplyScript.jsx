import { CodeBlock } from '../common/CodeBlock';
import { buildApplyScript } from '../../lib/applyScript';
import './ApplyScript.css';

export function ApplyScript({ recommendations, databaseType, catalog }) {
  const { script, runnableCount, reviewCount, dialect } = buildApplyScript(recommendations, {
    databaseType,
    catalog,
  });

  if (!script) return null;

  return (
    <div className="apply-script">
      <div className="apply-script__head">
        <span className="apply-script__title">Copy as one script</span>
        <span className="apply-script__counts">
          {runnableCount} runnable
          {reviewCount > 0 && <> · {reviewCount} for review</>}
        </span>
      </div>
      <CodeBlock code={script} dialect={dialect} />
    </div>
  );
}
