import { useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import {
  REFRESH_INTERVALS,
  RefreshIntervalContext,
  configuredRefreshInterval,
} from '../context/RefreshIntervalContext'
import { Icon } from './Icon'
import { useAuth } from '../auth/AuthContext'

const navItems = [
  { label: 'Overview', to: '/', icon: 'activity' as const, end: true },
  { label: 'Jobs', to: '/jobs', icon: 'terminal' as const },
]

function intervalLabel(interval: number): string {
  return `${interval / 1000}s`
}

export function DashboardLayout() {
  const { user, roles, logout } = useAuth()
  const [refreshInterval, setRefreshInterval] = useState(
    configuredRefreshInterval,
  )

  return (
    <RefreshIntervalContext.Provider value={refreshInterval}>
      <div className="app-shell">
        <aside className="sidebar">
          <Link aria-label="FlowForge home" className="brand" to="/">
            <span className="brand-mark">
              <span />
              <span />
              <span />
            </span>
            <span className="brand-name">flowforge</span>
            <span className="brand-tag">CONTROL PLANE</span>
          </Link>
          <div className="sidebar-section-label">WORKSPACE</div>
          <nav aria-label="Main navigation" className="sidebar-nav">
            {navItems.map(({ label, to, icon, end }) => (
              <NavLink
                className={({ isActive }) =>
                  `nav-link${isActive ? ' nav-link-active' : ''}`
                }
                end={end}
                key={label}
                to={to}
              >
                <Icon name={icon} />
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <a className="sidebar-doc-link" href="/swagger-ui/index.html">
              <Icon name="external" />
              API documentation
            </a>
            <div className="sidebar-user">
              <span className="avatar">
                {(user?.profile.preferred_username ?? 'FF').slice(0, 2).toUpperCase()}
              </span>
              <span>
                <strong>{user?.profile.preferred_username ?? 'FlowForge user'}</strong>
                <small>{roles.join(', ') || 'No assigned role'}</small>
              </span>
            </div>
          </div>
        </aside>

        <div className="main-column">
          <header className="topbar">
            <div className="breadcrumb">
              <span>Workspace</span>
              <Icon name="arrowRight" />
              <strong>Operations</strong>
            </div>
            <div className="topbar-actions">
              <span className="user-role">
                {roles.join(', ') || 'No assigned role'}
              </span>
              <button
                className="button button-secondary sign-out-button"
                onClick={() => void logout()}
                type="button"
              >
                Sign out
              </button>
              <label className="refresh-control">
                <Icon name="refresh" />
                <span className="refresh-label">Refresh</span>
                <select
                  aria-label="Refresh interval"
                  onChange={(event) =>
                    setRefreshInterval(Number(event.target.value))
                  }
                  value={refreshInterval}
                >
                  {REFRESH_INTERVALS.map((interval) => (
                    <option key={interval} value={interval}>
                      {intervalLabel(interval)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </header>
          <main className="main-content">
            <Outlet />
          </main>
          <footer className="app-footer">
            <span>FlowForge <span aria-hidden="true">·</span> Job orchestration</span>
            <span>PostgreSQL is the source of truth</span>
          </footer>
        </div>
      </div>
    </RefreshIntervalContext.Provider>
  )
}
