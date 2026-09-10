import { useEffect, useMemo, useState } from 'react';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';
import { HistoryTable } from '../components/history/HistoryTable';
import { getHistory, isLlmSource } from '../api';

export function HistoryPage() {
  const [records, setRecords] = useState([]);
  const [query, setQuery] = useState('');
  const [engineFilter, setEngineFilter] = useState('ALL');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getHistory()
      .then((res) => setRecords(res.records))
      .finally(() => setLoading(false));
  }, []);

  const filtered = useMemo(() => {
    return records
      .filter((r) => {
        const matchesQuery = query
          ? r.userInput.toLowerCase().includes(query.toLowerCase()) ||
            r.generatedQuery.toLowerCase().includes(query.toLowerCase())
          : true;
        // "LLM" is one user-facing choice covering both the current OLLAMA
        // source and GEMINI rows written before the provider rename, so the
        // filter doesn't silently hide half the fallback history.
        const matchesEngine =
          engineFilter === 'ALL' ||
          (engineFilter === 'LLM' ? isLlmSource(r.source) : r.source === engineFilter);
        return matchesQuery && matchesEngine;
      })
      .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  }, [records, query, engineFilter]);

  return (
    <div>
      <PageHeader title="History" description="Every query, its generated form, and which path handled it." />

      <div style={{ display: 'flex', gap: 'var(--space-3)', marginBottom: 'var(--space-4)' }}>
        <input
          className="qs-select__control"
          name="historySearch"
          style={{ flex: 1, background: 'var(--bg-sunken)', border: '1px solid var(--border-strong)', color: 'var(--ink)', padding: 'var(--space-2) var(--space-3)', borderRadius: 'var(--radius-sm)' }}
          placeholder="Search history"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <select
          className="qs-select__control"
          name="engineFilter"
          value={engineFilter}
          onChange={(e) => setEngineFilter(e.target.value)}
        >
          <option value="ALL">All engines</option>
          <option value="RULE_ENGINE">Rule engine</option>
          <option value="LLM">LLM fallback</option>
          <option value="CACHE">Cache</option>
        </select>
      </div>

      {loading && <EmptyState title="Loading history" />}
      {!loading && filtered.length === 0 && <EmptyState title="No matching history" detail="Try clearing the search or filter." />}
      {!loading && filtered.length > 0 && <HistoryTable records={filtered} />}
    </div>
  );
}
