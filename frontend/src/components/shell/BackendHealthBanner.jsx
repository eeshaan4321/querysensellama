import './BackendHealthBanner.css';

/** Shell-wide notice for a backend that is not answering, so a dead API reads as one banner. */
export function BackendHealthBanner({ status, mongo, baseUrl, onRetry }) {
  if (status === 'mock') {
    return (
      <div className="backend-health backend-health--mock" role="status">
        <span className="backend-health__dot" aria-hidden="true" />
        <span>
          Mock mode is on — every screen is showing fixtures from <code>src/mock</code>, not your backend.
        </span>
      </div>
    );
  }

  if (status === 'up' && (mongo === 'DOWN' || mongo === 'NOT_CONFIGURED')) {
    return (
      <div className="backend-health backend-health--mongo" role="status">
        <span className="backend-health__dot" aria-hidden="true" />
        <span>
          {mongo === 'DOWN'
            ? 'MongoDB is not answering. MySQL keeps working; MongoDB analysis, comparison and uploads return an error until mongod is running again.'
            : 'MongoDB is not configured — spring.data.mongodb.uri is empty — so MongoDB targets cannot be analysed or uploaded. MySQL keeps working.'}
        </span>
        {onRetry ? (
          <button type="button" className="backend-health__retry" onClick={onRetry}>
            Retry
          </button>
        ) : null}
      </div>
    );
  }

  if (status !== 'down') return null;

  return (
    <div className="backend-health backend-health--down" role="alert">
      <span className="backend-health__dot" aria-hidden="true" />
      <span>
        No answer from the backend{baseUrl ? <> at <code>{baseUrl}</code></> : null}. Screens below are
        empty because nothing could be fetched — they are not showing stale or mock data.
      </span>
      {onRetry ? (
        <button type="button" className="backend-health__retry" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </div>
  );
}
