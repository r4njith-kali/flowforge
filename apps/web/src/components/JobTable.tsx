import { Link } from 'react-router-dom'
import type { Job, JobFilters } from '../types/job'
import { Icon } from './Icon'
import { StatusBadge } from './StatusBadge'

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function JobTable({
  jobs,
  sortBy,
  direction,
  onSort,
}: {
  jobs: Job[]
  sortBy: NonNullable<JobFilters['sortBy']>
  direction: NonNullable<JobFilters['direction']>
  onSort: (field: NonNullable<JobFilters['sortBy']>) => void
}) {
  const sortable = (
    label: string,
    field: NonNullable<JobFilters['sortBy']>,
  ) => (
    <button
      aria-label={`Sort by ${label}`}
      className="table-sort"
      onClick={() => onSort(field)}
      type="button"
    >
      {label}
      <Icon
        className={`sort-icon ${sortBy === field ? 'sort-active' : ''}`}
        name="sort"
      />
      {sortBy === field && <span className="sr-only">({direction})</span>}
    </button>
  )

  return (
    <div className="table-scroll">
      <table className="jobs-table">
        <thead>
          <tr>
            <th>{sortable('Job type', 'jobType')}</th>
            <th>{sortable('Status', 'status')}</th>
            <th>Job ID</th>
            <th>{sortable('Created', 'createdAt')}</th>
            <th>Attempts</th>
            <th aria-label="Open job" />
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => (
            <tr key={job.id}>
              <td>
                <Link className="job-type-link" to={`/jobs/${job.id}`}>
                  <span className="job-type-icon">
                    <Icon name="terminal" />
                  </span>
                  <span>{job.jobType}</span>
                </Link>
              </td>
              <td>
                <StatusBadge status={job.status} />
              </td>
              <td>
                <code className="id-code">{job.id.slice(0, 8)}</code>
              </td>
              <td className="muted-cell">{formatDate(job.createdAt)}</td>
              <td>
                <span className="attempt-count">
                  {job.attemptCount}
                  <span> / {job.maxAttempts}</span>
                </span>
              </td>
              <td>
                <Link
                  aria-label={`View ${job.jobType} job`}
                  className="row-link"
                  to={`/jobs/${job.id}`}
                >
                  <Icon name="arrowRight" />
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
