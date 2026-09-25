import { Link, useLocation } from 'react-router-dom';
import { PageHeader } from '../components/common/PageHeader';
import { EmptyState } from '../components/common/EmptyState';

/** Catch-all route, so a stale link reads as a missing page rather than an empty shell. */
export function NotFoundPage() {
  const { pathname } = useLocation();

  return (
    <div>
      <PageHeader title="Not found" description="This address does not match a screen in QuerySense." />
      <EmptyState
        title="There is no page at this address"
        detail={`Nothing is routed to ${pathname}. If you followed a link from an older build, the screen it pointed at has been removed.`}
        action={<Link to="/">Back to the Workbench</Link>}
      />
    </div>
  );
}
