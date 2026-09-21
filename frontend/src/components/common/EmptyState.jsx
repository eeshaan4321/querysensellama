import './EmptyState.css';

export function EmptyState({ title, detail, action }) {
  return (
    <div className="empty-state">
      <p className="empty-state__title">{title}</p>
      {detail && <p className="empty-state__detail">{detail}</p>}
      {action && <div className="empty-state__action">{action}</div>}
    </div>
  );
}
