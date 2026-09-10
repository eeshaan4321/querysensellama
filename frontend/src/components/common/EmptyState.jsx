import './EmptyState.css';

export function EmptyState({ title, detail }) {
  return (
    <div className="empty-state">
      <p className="empty-state__title">{title}</p>
      {detail && <p className="empty-state__detail">{detail}</p>}
    </div>
  );
}
