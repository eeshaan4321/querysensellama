import { EngineBadge } from '../common/EngineBadge';
import './HistoryTable.css';

function formatTime(iso) {
  const d = new Date(iso);
  return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

const EMPTY = '—';

function formatConfidence(value) {
  return value == null ? EMPTY : `${Math.round(value * 100)}%`;
}

function formatDuration(ms) {
  return ms == null ? EMPTY : `${ms.toLocaleString()} ms`;
}

export function HistoryTable({ records }) {
  return (
    <table className="history-table">
      <thead>
        <tr>
          <th>Request</th>
          <th>Generated query</th>
          <th>Handled by</th>
          <th>Confidence</th>
          <th>Schema</th>
          <th>Took</th>
          <th>When</th>
        </tr>
      </thead>
      <tbody>
        {records.map((r) => (
          <tr key={r.id}>
            <td className="history-table__nl">{r.userInput}</td>
            <td className="history-table__query"><code>{r.generatedQuery}</code></td>
            <td><EngineBadge handledBy={r.source} size="sm" /></td>
            <td className="history-table__meta">{formatConfidence(r.confidence)}</td>
            <td className="history-table__meta">{r.schemaLabel || EMPTY}</td>
            <td className="history-table__meta">{formatDuration(r.durationMs)}</td>
            <td className="history-table__time">{formatTime(r.createdAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
