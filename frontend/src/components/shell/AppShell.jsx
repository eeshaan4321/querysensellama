import { Outlet } from 'react-router-dom';
import { NavRail } from './NavRail';
import { EngineStatusBar } from './EngineStatusBar';
import './AppShell.css';

export function AppShell() {
  return (
    <div className="app-shell">
      <NavRail />
      <div className="app-shell__main">
        <EngineStatusBar />
        <div className="app-shell__content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}
