import type { JobStatus } from '../types/job'

const statusLabels: Record<JobStatus, string> = {
  PENDING: 'Pending',
  SCHEDULED: 'Scheduled',
  DISPATCHING: 'Dispatching',
  DISPATCHED: 'Dispatched',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span className={`status-badge status-${status.toLowerCase()}`}>
      <span className="status-dot" />
      {statusLabels[status]}
    </span>
  )
}
