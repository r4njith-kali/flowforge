import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { Job } from '../types/job'
import { RefreshIntervalContext } from '../context/RefreshIntervalContext'
import { JobDetailPage } from './JobDetailPage'

const { cancelJob, getJob } = vi.hoisted(() => ({
  cancelJob: vi.fn(),
  getJob: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, cancelJob, getJob }
})

const runningJob: Job = {
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
  attemptCount: 1,
  maxAttempts: 5,
  retryDelaySeconds: 0,
  version: 2,
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

  it('does not present unavailable execution results as real data', async () => {
    renderDetail()

    expect(await screen.findByText('Execution result')).toBeInTheDocument()
    expect(
      screen.getByText(/Result data is not available through the current API/),
    ).toBeInTheDocument()
    expect(screen.queryByText('Worker output')).not.toBeInTheDocument()
  })
})
