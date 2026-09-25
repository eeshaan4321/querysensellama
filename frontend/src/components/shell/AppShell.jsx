import { Outlet } from 'react-router-dom';
import { NavRail } from './NavRail';
import { EngineStatusBar } from './EngineStatusBar';
import { BackendHealthBanner } from './BackendHealthBanner';
import { useBackendHealth } from '../../hooks/useBackendHealth';
import './AppShell.css';

export function AppShell() {
  const { status, refresh } = useBackendHealth();

  return (
    <div className="app-shell">
      <NavRail />
      <div className="app-shell__main">
        <EngineStatusBar />
        <BackendHealthBanner
          status={status}
          baseUrl={import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'}
          onRetry={refresh}
        />
        <div className="app-shell__content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
