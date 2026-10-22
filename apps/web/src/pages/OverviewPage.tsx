import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getControlPlaneHealth, listJobs } from '../api/client'
import { EmptyState, ErrorNotice } from '../components/Feedback'
import { Icon } from '../components/Icon'
import { JobTable } from '../components/JobTable'
import { useRefreshInterval } from '../context/RefreshIntervalContext'
import { usePollingQuery } from '../hooks/usePollingQuery'
import type { JobPage } from '../types/job'
import { useAuth } from '../auth/AuthContext'

interface OverviewData {
  recent: JobPage
  total: number
  running: number
  completed: number
  failed: number
}

function useOverview() {
  const loader = useCallback(async (): Promise<OverviewData> => {
    const [recent, all, running, completed, failed] = await Promise.all([
      listJobs({ page: 0, size: 5, sortBy: 'createdAt', direction: 'DESC' }),
      listJobs({ page: 0, size: 1 }),
      listJobs({ page: 0, size: 1, status: 'RUNNING' }),
      listJobs({ page: 0, size: 1, status: 'COMPLETED' }),
      listJobs({ page: 0, size: 1, status: 'FAILED' }),
    ])
    return {
      recent,
      total: all.totalElements,
      running: running.totalElements,
      completed: completed.totalElements,
      failed: failed.totalElements,
    }
  }, [])
  return usePollingQuery(loader, useRefreshInterval())
}

function MetricCard({
  label,
  value,
  icon,
  tone,
  detail,
}: {
  label: string
  value: number | string
  icon: 'activity' | 'clock' | 'check' | 'warning'
  tone: string
  detail: string
}) {
  return (
    <article className="metric-card">
      <div className="metric-top">
        <span className="metric-label">{label}</span>
        <span className={`metric-icon metric-icon-${tone}`}>
          <Icon name={icon} />
        </span>
      </div>
      <div className="metric-value">{value}</div>
      <div className="metric-detail">{detail}</div>
    </article>
  )
}

export function OverviewPage() {
  const { canSubmit } = useAuth()
  const { data, error, isLoading, refresh } = useOverview()
  const health = usePollingQuery(
    getControlPlaneHealth,
    useRefreshInterval(),
  )

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / OVERVIEW</p>
          <h1>Good work starts with visibility.</h1>
          <p className="page-subtitle">
            Monitor your jobs and execution activity from one place.
          </p>
        </div>
        {canSubmit && (
          <Link className="button button-primary" to="/jobs/new">
            <Icon name="plus" />
            Submit a job
          </Link>
        )}
      </div>

      <section aria-label="System status" className="system-banner">
        <span
          className={`system-indicator ${health.data?.status === 'UP' ? 'system-up' : health.data ? 'system-down' : 'system-unknown'}`}
        />
        <span className="system-title">Control plane</span>
        <span className="system-state">
          {health.isLoading
            ? 'Checking health…'
            : health.data?.status === 'UP'
              ? 'Operational'
              : health.error
                ? 'Health check unavailable'
                : health.data?.status ?? 'Unknown'}
        </span>
        <span className="system-spacer" />
        <span className="system-health-caption">
          <Icon name="shield" />
          Live health endpoint
        </span>
      </section>

      {health.error && (
        <ErrorNotice title="Control-plane health check failed">
          {health.error.message}
        </ErrorNotice>
      )}
      {error && !data && (
        <ErrorNotice onRetry={refresh}>{error.message}</ErrorNotice>
      )}
      {error && data && (
        <ErrorNotice title="Could not refresh job metrics" onRetry={refresh}>
          Showing the last successful response. {error.message}
        </ErrorNotice>
      )}

      <section aria-label="Job metrics" className="metrics-grid">
        <MetricCard
          detail="All jobs in the API"
          icon="activity"
          label="Total jobs"
          tone="violet"
          value={data?.total ?? (isLoading ? '—' : 0)}
        />
        <MetricCard
          detail="Currently executing"
          icon="clock"
          label="Running now"
          tone="blue"
          value={data?.running ?? (isLoading ? '—' : 0)}
        />
        <MetricCard
          detail="Persisted as completed"
          icon="check"
          label="Completed"
          tone="green"
          value={data?.completed ?? (isLoading ? '—' : 0)}
        />
        <MetricCard
          detail="Requires investigation"
          icon="warning"
          label="Failed"
          tone="amber"
          value={data?.failed ?? (isLoading ? '—' : 0)}
        />
      </section>

      <section className="panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">LATEST ACTIVITY</p>
            <h2>Recent jobs</h2>
          </div>
          <Link className="text-link" to="/jobs">
            View all jobs <Icon name="arrowRight" />
          </Link>
        </div>
        {isLoading && !data ? (
          <div aria-label="Loading recent jobs" className="table-loading">
            <span className="spinner" />
            Loading jobs…
          </div>
        ) : data?.recent.items.length ? (
          <JobTable
            direction="DESC"
            jobs={data.recent.items}
            onSort={() => undefined}
            sortBy="createdAt"
          />
        ) : (
          <EmptyState
            action={canSubmit ? (
              <Link className="button button-secondary" to="/jobs/new">
                Submit your first job
              </Link>
            ) : undefined}
            description="New jobs will appear here as soon as they are submitted."
            title="No jobs yet"
          />
        )}
      </section>
      <p className="data-note">
        Metrics and job execution details reflect persisted control-plane API
        data.
      </p>
    </div>
  )
}
