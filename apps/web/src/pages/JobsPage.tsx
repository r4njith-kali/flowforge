import { useCallback, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { listJobs } from '../api/client'
import { EmptyState, ErrorNotice } from '../components/Feedback'
import { Icon } from '../components/Icon'
import { JobTable } from '../components/JobTable'
import { JOB_STATUSES, type JobFilters, type JobStatus } from '../types/job'
import { useRefreshInterval } from '../context/RefreshIntervalContext'
import { usePollingQuery } from '../hooks/usePollingQuery'

export function JobsPage() {
  const interval = useRefreshInterval()
  const [status, setStatus] = useState<JobStatus | ''>('')
  const [jobTypeInput, setJobTypeInput] = useState('')
  const [jobType, setJobType] = useState('')
  const [page, setPage] = useState(0)
  const [sortBy, setSortBy] =
    useState<NonNullable<JobFilters['sortBy']>>('createdAt')
  const [direction, setDirection] =
    useState<NonNullable<JobFilters['direction']>>('DESC')

  const loader = useCallback(
    () =>
      listJobs({
        page,
        size: 10,
        status: status || undefined,
        jobType: jobType || undefined,
        sortBy,
        direction,
      }),
    [direction, jobType, page, sortBy, status],
  )
  const { data, error, isLoading, refresh } = usePollingQuery(loader, interval)

  const changeSort = (field: NonNullable<JobFilters['sortBy']>) => {
    if (sortBy === field) setDirection((current) => current === 'ASC' ? 'DESC' : 'ASC')
    else {
      setSortBy(field)
      setDirection('DESC')
    }
    setPage(0)
  }

  const submitFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setJobType(jobTypeInput.trim())
    setPage(0)
  }

  return (
    <div className="page-stack">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / JOBS</p>
          <h1>Jobs</h1>
          <p className="page-subtitle">
            Search, filter, and inspect work in your orchestration system.
          </p>
        </div>
        <Link className="button button-primary" to="/jobs/new">
          <Icon name="plus" />
          Submit a job
        </Link>
      </div>

      <section className="panel jobs-panel">
        <form className="filter-bar" onSubmit={submitFilters}>
          <label className="search-field">
            <Icon name="search" />
            <span className="sr-only">Filter by job type</span>
            <input
              onChange={(event) => setJobTypeInput(event.target.value)}
              placeholder="Filter by job type…"
              value={jobTypeInput}
            />
          </label>
          <label className="filter-select">
            <Icon name="filter" />
            <span className="sr-only">Filter by status</span>
            <select
              onChange={(event) => {
                setStatus(event.target.value as JobStatus | '')
                setPage(0)
              }}
              value={status}
            >
              <option value="">All statuses</option>
              {JOB_STATUSES.map((jobStatus) => (
                <option key={jobStatus} value={jobStatus}>
                  {jobStatus.replaceAll('_', ' ')}
                </option>
              ))}
            </select>
          </label>
          <button className="button button-secondary filter-submit" type="submit">
            Apply filters
          </button>
          <span className="filter-count">
            {data ? `${data.totalElements.toLocaleString()} jobs` : '—'}
          </span>
        </form>

        {error && !data && <ErrorNotice onRetry={refresh}>{error.message}</ErrorNotice>}
        {error && data && (
          <ErrorNotice title="Could not refresh jobs" onRetry={refresh}>
            Showing the last successful response. {error.message}
          </ErrorNotice>
        )}
        {isLoading && !data ? (
          <div aria-label="Loading jobs" className="table-loading">
            <span className="spinner" />
            Loading jobs…
          </div>
        ) : data?.items.length ? (
          <>
            <JobTable
              direction={direction}
              jobs={data.items}
              onSort={changeSort}
              sortBy={sortBy}
            />
            <div aria-label="Job pagination" className="pagination">
              <span>
                Showing{' '}
                <strong>
                  {data.totalElements === 0 ? 0 : data.page * data.size + 1}
                  {'–'}
                  {Math.min((data.page + 1) * data.size, data.totalElements)}
                </strong>{' '}
                of <strong>{data.totalElements}</strong>
              </span>
              <div className="pagination-actions">
                <button
                  aria-label="Previous page"
                  className="icon-button"
                  disabled={data.first}
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                  type="button"
                >
                  <Icon name="arrowLeft" />
                </button>
                <span>
                  Page {data.totalPages === 0 ? 0 : data.page + 1} of {data.totalPages}
                </span>
                <button
                  aria-label="Next page"
                  className="icon-button"
                  disabled={data.last}
                  onClick={() => setPage((current) => current + 1)}
                  type="button"
                >
                  <Icon name="arrowRight" />
                </button>
              </div>
            </div>
          </>
        ) : (
          <EmptyState
            action={
              <Link className="button button-secondary" to="/jobs/new">
                Submit a job
              </Link>
            }
            description={
              status || jobType
                ? 'Try clearing or changing your filters.'
                : 'Submit a job to see it appear in this list.'
            }
            title={status || jobType ? 'No matching jobs' : 'No jobs yet'}
          />
        )}
      </section>
      <p className="data-note">
        Job data is loaded from the control-plane API. Results are ordered by{' '}
        {sortBy} ({direction.toLowerCase()}).
      </p>
    </div>
  )
}
