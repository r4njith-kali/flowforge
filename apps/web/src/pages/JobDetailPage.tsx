import { useCallback, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { ApiError, cancelJob, getJob } from '../api/client'
import { ErrorNotice } from '../components/Feedback'
import { Icon } from '../components/Icon'
import { StatusBadge } from '../components/StatusBadge'
import { useRefreshInterval } from '../context/RefreshIntervalContext'
import { usePollingQuery } from '../hooks/usePollingQuery'
import type { Job, JobStatus } from '../types/job'

const terminalStatuses: JobStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED']

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(new Date(value))
}

function relativeTime(value: string): string {
  const difference = Math.round((new Date(value).getTime() - Date.now()) / 1000)
  const absoluteSeconds = Math.abs(difference)
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
  if (absoluteSeconds < 60) return formatter.format(difference, 'second')
  const minutes = Math.round(difference / 60)
  if (Math.abs(minutes) < 60) return formatter.format(minutes, 'minute')
  return formatter.format(Math.round(difference / 3600), 'hour')
}

interface TimelineItem {
  label: string
  timestamp: string
  description: string
}

function timelineItems(job: Job): TimelineItem[] {
  const items: TimelineItem[] = [
    {
      label: 'Job created',
      timestamp: job.createdAt,
      description: 'Accepted and persisted by the control plane.',
    },
  ]
  if (job.scheduledAt) {
    items.push({
      label: 'Scheduled for',
      timestamp: job.scheduledAt,
      description: 'Configured eligibility time; this is not an execution event.',
    })
  }
  if (job.startedAt) {
    items.push({
      label: 'Execution started',
      timestamp: job.startedAt,
      description: 'Start time recorded by the worker runtime.',
    })
  }
  if (job.cancelRequestedAt) {
    items.push({
      label: 'Cancellation requested',
      timestamp: job.cancelRequestedAt,
      description: 'Cancellation request recorded by the control plane.',
    })
  }
  if (job.completedAt) {
    items.push({
      label: 'Execution finished',
      timestamp: job.completedAt,
      description: `Job is currently ${job.status.toLowerCase()}.`,
    })
  }
  return items.sort(
    (left, right) =>
      new Date(left.timestamp).getTime() - new Date(right.timestamp).getTime(),
  )
}

function MetadataRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="metadata-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

export function JobDetailPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const interval = useRefreshInterval()
  const loader = useCallback(() => getJob(id), [id])
  const { data: job, error, isLoading, refresh, setData } = usePollingQuery(
    loader,
    interval,
  )
  const [actionMessage, setActionMessage] = useState<string | null>(
    (location.state as { notice?: string } | null)?.notice ?? null,
  )
  const [actionError, setActionError] = useState<Error | null>(null)
  const [isCancelling, setIsCancelling] = useState(false)

  const requestCancellation = async () => {
    if (!job || !window.confirm(`Cancel job ${job.id}?`)) return
    setActionMessage(null)
    setActionError(null)
    setIsCancelling(true)
    try {
      const result = await cancelJob(job.id)
      setData(result.job)
      setActionMessage(
        result.accepted
          ? 'Cancellation accepted. The job is still running until the worker stops cooperatively.'
          : result.job.status === 'CANCELLED'
            ? 'Job cancelled successfully.'
            : `Cancellation response received. Current status: ${result.job.status}.`,
      )
      refresh()
    } catch (caught) {
      setActionError(
        caught instanceof ApiError && caught.status === 409
          ? new Error(
              `Cancellation conflict: ${caught.message} Refresh the job to see its current state.`,
            )
          : caught instanceof Error
            ? caught
            : new Error('Cancellation failed.'),
      )
      refresh()
    } finally {
      setIsCancelling(false)
    }
  }

  if (isLoading && !job) {
    return (
      <div aria-label="Loading job" className="page-loading">
        <span className="spinner" />
        Loading job details…
      </div>
    )
  }
  if (error && !job) {
    return (
      <div className="page-stack">
        <Link className="back-link" to="/jobs">
          <Icon name="arrowLeft" /> Back to jobs
        </Link>
        <ErrorNotice onRetry={refresh}>{error.message}</ErrorNotice>
      </div>
    )
  }
  if (!job) return null

  const canCancel = !terminalStatuses.includes(job.status)
  const timeline = timelineItems(job)

  return (
    <div className="page-stack">
      <Link className="back-link" to="/jobs">
        <Icon name="arrowLeft" /> Back to jobs
      </Link>
      {error && (
        <ErrorNotice title="Could not refresh job status" onRetry={refresh}>
          Showing the last successful response. {error.message}
        </ErrorNotice>
      )}
      {actionMessage && (
        <div className="notice notice-success" role="status">
          <Icon name="check" />
          <p>{actionMessage}</p>
        </div>
      )}
      {actionError && (
        <ErrorNotice title="Job action failed" onRetry={refresh}>
          {actionError.message}
        </ErrorNotice>
      )}

      <div className="detail-heading">
        <div className="detail-title-wrap">
          <span className="detail-type-icon">
            <Icon name="terminal" />
          </span>
          <div>
            <p className="eyebrow">JOB DETAILS</p>
            <h1>{job.jobType}</h1>
            <code className="detail-id">{job.id}</code>
          </div>
        </div>
        <div className="detail-actions">
          <StatusBadge status={job.status} />
          {canCancel && (
            <button
              className="button button-danger"
              disabled={isCancelling}
              onClick={() => void requestCancellation()}
              type="button"
            >
              <Icon name="x" />
              {isCancelling
                ? 'Cancelling…'
                : job.status === 'RUNNING'
                  ? 'Request cancellation'
                  : 'Cancel job'}
            </button>
          )}
        </div>
      </div>

      <div className="detail-grid">
        <div className="detail-main-column">
          <section className="panel">
            <div className="panel-heading panel-heading-compact">
              <div>
                <p className="eyebrow">LIFECYCLE</p>
                <h2>Execution timeline</h2>
              </div>
              <span className="live-poll-indicator">
                <span /> Updates every {interval / 1000}s
              </span>
            </div>
            <ol aria-label="Execution timeline" className="timeline">
              {timeline.map((item, index) => (
                <li className="timeline-item" key={`${item.label}-${item.timestamp}`}>
                  <span className={`timeline-marker ${index === timeline.length - 1 ? 'timeline-marker-current' : ''}`}>
                    {index === timeline.length - 1 ? <span /> : <Icon name="check" />}
                  </span>
                  <div className="timeline-content">
                    <div className="timeline-topline">
                      <strong>{item.label}</strong>
                      <time dateTime={item.timestamp} title={formatDate(item.timestamp)}>
                        {relativeTime(item.timestamp)}
                      </time>
                    </div>
                    <p>{item.description}</p>
                    <small>{formatDate(item.timestamp)}</small>
                  </div>
                </li>
              ))}
            </ol>
            <p className="timeline-note">
              Timeline entries come only from timestamps exposed by the API.
              Dispatch and attempt-history events are not currently available.
            </p>
          </section>

          <section className="panel">
            <div className="panel-heading panel-heading-compact">
              <div>
                <p className="eyebrow">INPUT</p>
                <h2>Payload</h2>
              </div>
              <span className="language-tag">JSON</span>
            </div>
            <pre className="json-block"><code>{JSON.stringify(job.payload, null, 2)}</code></pre>
          </section>

          <section className="panel">
            <div className="panel-heading panel-heading-compact">
              <div>
                <p className="eyebrow">OUTPUT</p>
                <h2>Execution result</h2>
              </div>
              <span className="not-exposed-tag">Not exposed</span>
            </div>
            <div className="unavailable-block">
              <Icon name="shield" />
              <div>
                <strong>Result data is not available through the current API.</strong>
                <p>
                  FlowForge persists worker results, but this response contract
                  does not include them.
                </p>
              </div>
            </div>
          </section>
        </div>

        <aside className="detail-side-column">
          <section className="panel metadata-panel">
            <div className="panel-heading panel-heading-compact">
              <div>
                <p className="eyebrow">RECORD</p>
                <h2>Metadata</h2>
              </div>
            </div>
            <dl className="metadata-list">
              <MetadataRow label="Current status" value={job.status} />
              <MetadataRow label="Created" value={formatDate(job.createdAt)} />
              <MetadataRow label="Last updated" value={formatDate(job.updatedAt)} />
              <MetadataRow label="Scheduled for" value={formatDate(job.scheduledAt)} />
              <MetadataRow label="Started at" value={formatDate(job.startedAt)} />
              <MetadataRow label="Finished at" value={formatDate(job.completedAt)} />
              <MetadataRow
                label="Cancellation requested"
                value={formatDate(job.cancelRequestedAt)}
              />
              <MetadataRow
                label="Attempts"
                value={`${job.attemptCount} of ${job.maxAttempts}`}
              />
              <MetadataRow
                label="Base retry delay"
                value={`${job.retryDelaySeconds} seconds`}
              />
              <MetadataRow label="Record version" value={String(job.version)} />
            </dl>
          </section>
          <section className="panel retry-card">
            <span className="retry-icon"><Icon name="timer" /></span>
            <h3>Retry policy</h3>
            <p>
              Up to <strong>{job.maxAttempts} attempts</strong> with a base delay
              of <strong>{job.retryDelaySeconds}s</strong>. Actual retry timing
              is controlled by the worker runtime.
            </p>
          </section>
        </aside>
      </div>
    </div>
  )
}
