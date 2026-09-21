import { tokenize } from '../../lib/highlight';
import './CodeBlock.css';

export function CodeBlock({ code, dialect = 'SQL', label }) {
  const tokens = tokenize(code, dialect);

  return (
    <div className="code-block">
      {label && <div className="code-block__label">{label}</div>}
      <pre className="code-block__pre">
        <code>
          {tokens.map((t, i) => (
            <span key={i} className={t.type !== 'plain' ? `tok tok--${t.type}` : undefined}>
              {t.text}
            </span>
          ))}
        </code>
      </pre>
      <button
        type="button"
        className="code-block__copy"
        onClick={() => navigator.clipboard?.writeText(code)}
      >
        Copy
      </button>
    </div>
  );
}
