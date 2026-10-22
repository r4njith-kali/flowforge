import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { JobAttempt, JobDetail } from '../types/job'
import { RefreshIntervalContext } from '../context/RefreshIntervalContext'
import { JobDetailPage } from './JobDetailPage'

const { cancelJob, getJob, getJobAttempts } = vi.hoisted(() => ({
  cancelJob: vi.fn(),
  getJob: vi.fn(),
  getJobAttempts: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, cancelJob, getJob, getJobAttempts }
})
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ canCancel: true }),
}))

const runningJob: JobDetail = {
  id: '8f32f376-b61b-4b8f-9b45-c2cb1ab2b911',
  jobType: 'demo.echo',
  payload: { message: 'hello' },
  status: 'RUNNING',
  createdAt: '2026-10-13T12:00:00Z',
  updatedAt: '2026-10-13T12:00:02Z',
  scheduledAt: null,
  startedAt: '2026-10-13T12:00:02Z',
  completedAt: null,
  cancelRequestedAt: null,
  result: null,
  error: null,
  attemptCount: 1,
  maxAttempts: 5,
  retryDelaySeconds: 0,
  version: 2,
}

const retryAttempt: JobAttempt = {
  id: '135a5d76-1830-4c5e-84f1-15c975bfcce0',
  attemptNumber: 1,
  workerId: 'worker-1',
  status: 'RETRY_WAIT',
  startedAt: '2026-10-13T12:00:02Z',
  heartbeatAt: '2026-10-13T12:00:03Z',
  leaseExpiresAt: '2026-10-13T12:00:30Z',
  finishedAt: '2026-10-13T12:00:05Z',
  failureType: 'TRANSIENT',
  error: { message: 'Temporary worker failure' },
  result: null,
}

function renderDetail() {
  return render(
    <RefreshIntervalContext.Provider value={3_600_000}>
      <MemoryRouter initialEntries={[`/jobs/${runningJob.id}`]}>
        <Routes>
          <Route element={<JobDetailPage />} path="/jobs/:id" />
        </Routes>
      </MemoryRouter>
    </RefreshIntervalContext.Provider>,
  )
}

describe('JobDetailPage cancellation', () => {
  beforeEach(() => {
    getJob.mockResolvedValue(runningJob)
    getJobAttempts.mockResolvedValue([])
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  it('reports HTTP 202 as an accepted request, not a completed cancellation', async () => {
    cancelJob.mockResolvedValue({ job: runningJob, accepted: true })
    renderDetail()
    await screen.findByRole('heading', { name: 'demo.echo' })

    fireEvent.click(screen.getByRole('button', { name: 'Request cancellation' }))

    expect(
      await screen.findByText(
        /Cancellation accepted\. The job is still running until the worker stops cooperatively\./,
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Running')).toBeInTheDocument()
  })

  it('displays a 409 conflict without implying that cancellation succeeded', async () => {
    cancelJob.mockRejectedValue(
      new ApiError('Job is already completed', 409, 'JOB_STATE_CONFLICT'),
    )
    renderDetail()
    await screen.findByRole('heading', { name: 'demo.echo' })

    fireEvent.click(screen.getByRole('button', { name: 'Request cancellation' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        /Cancellation conflict: Job is already completed/,
      )
    })
    expect(screen.queryByText('Job cancelled successfully.')).not.toBeInTheDocument()
  })

  it('renders job outcomes and persisted attempt details', async () => {
    getJob.mockResolvedValue({
      ...runningJob,
      result: { response: 'hello' },
      error: { failureType: 'TRANSIENT', message: 'Earlier failure' },
    })
    getJobAttempts.mockResolvedValue([retryAttempt])
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Job result' })).toBeInTheDocument()
    expect(screen.getByText(/"response": "hello"/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Job error' })).toBeInTheDocument()
    expect(screen.getByText(/"message": "Earlier failure"/)).toBeInTheDocument()
    expect(await screen.findByText('Attempt 1')).toBeInTheDocument()
    expect(screen.getByText('worker-1')).toBeInTheDocument()
    expect(screen.getByText('TRANSIENT')).toBeInTheDocument()
    expect(screen.getByText(/"message": "Temporary worker failure"/)).toBeInTheDocument()
  })

  it('shows an empty state when a job has no result or attempts', async () => {
    renderDetail()

    expect(
      await screen.findByText('No execution result or error has been recorded.'),
    ).toBeInTheDocument()
    expect(
      await screen.findByText('No worker attempts have been recorded yet.'),
    ).toBeInTheDocument()
  })

  it('keeps job details and actions available when attempt history fails', async () => {
    getJobAttempts.mockRejectedValue(new Error('Attempt service unavailable'))
    renderDetail()

    expect(await screen.findByRole('alert')).toHaveTextContent('Attempt service unavailable')
    expect(screen.getByText('Running')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request cancellation' })).toBeInTheDocument()
  })
})
