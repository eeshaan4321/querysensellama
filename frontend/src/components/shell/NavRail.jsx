import { NavLink } from 'react-router-dom';
import { USE_MOCK } from '../../api';
import './NavRail.css';

const ICONS = {
  workbench: (
    <path d="M3 12h4l2-6 4 12 2-6h4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />
  ),
  schemaSource: (
    <path d="M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />
  ),
  analysis: (
    <path d="M4 20V10M10 20V4M16 20v-7M20 20H2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" fill="none" />
  ),
  optimization: (
    <path d="M4 17l5-5 4 4 7-8M20 8v6M20 8h-6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />
  ),
  schema: (
    <path d="M4 5h16M4 12h10M4 19h13" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" fill="none" />
  ),
  history: (
    <path d="M12 6v6l4 2M21 12a9 9 0 11-3-6.7" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />
  ),
  about: (
    <path d="M9 9a3 3 0 116 0c0 2-3 2.5-3 5M12 17.5v.01M4 12a8 8 0 1116 0 8 8 0 01-16 0z" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" fill="none" />
  ),
};

const ITEMS = [
  { to: '/', label: 'Workbench', icon: 'workbench', end: true },
  { to: '/schema-source', label: 'Schema source', icon: 'schemaSource' },
  { to: '/analysis', label: 'Analysis', icon: 'analysis' },
  { to: '/optimization', label: 'Optimization', icon: 'optimization' },
  { to: '/schema', label: 'Schema advisor', icon: 'schema' },
  { to: '/history', label: 'History', icon: 'history' },
  { to: '/about', label: 'Architecture', icon: 'about' },
];

export function NavRail() {
  return (
    <nav className="nav-rail" aria-label="Primary">
      <div className="nav-rail__brand">
        <span className="nav-rail__mark">QS</span>
        <div>
          <div className="nav-rail__title">QuerySense</div>
          <div className="nav-rail__subtitle">query advisory system</div>
        </div>
      </div>

      <ul className="nav-rail__list">
        {ITEMS.map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              end={item.end}
              className={({ isActive }) => `nav-rail__link${isActive ? ' nav-rail__link--active' : ''}`}
            >
              <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
                {ICONS[item.icon]}
              </svg>
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>

      <div className="nav-rail__footer">
        <span className={`nav-rail__mode-dot${USE_MOCK ? '' : ' nav-rail__mode-dot--live'}`} aria-hidden="true" />
        <span>{USE_MOCK ? 'Running on mock data' : 'Connected to backend'}</span>
      </div>
    </nav>
  );
}
