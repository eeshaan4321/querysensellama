import { PageHeader } from '../components/common/PageHeader';
import { PipelineDiagram } from '../components/architecture/PipelineDiagram';

export function AboutPage() {
  return (
    <div>
      <PageHeader
        title="Architecture"
        description="A hybrid, rule-based-first system. The rule engine handles most queries directly; a local LLM (Ollama, qwen2.5-coder:7b) is a fallback for low-confidence cases — an explicit design choice, not a shortcut."
      />
      <PipelineDiagram />

      <div style={{ marginTop: 'var(--space-7)', display: 'flex', flexDirection: 'column', gap: 'var(--space-3)', maxWidth: '40rem' }}>
        <p style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', lineHeight: 'var(--leading-relaxed)' }}>
          Interpretability was the deciding factor. MySQL EXPLAIN analysis, JSQL Parser
          structural parsing, and domain/resource profiles cover the majority of
          queries with results a person can verify by hand. An LLM only steps in when
          the rule engine's own confidence is low — comparisons, joins, aggregations,
          or ambiguous time expressions.
        </p>
        <p style={{ fontSize: 'var(--text-sm)', color: 'var(--ink-secondary)', lineHeight: 'var(--leading-relaxed)' }}>
          Every downstream module — the analysis engine, the optimizers, the schema
          advisor, the comparison engine — consumes the same shared contract regardless
          of which path produced the query, so the rest of the pipeline never needs to
          know whether a query came from the rule engine or from the LLM fallback.
        </p>
      </div>
    </div>
  );
}
