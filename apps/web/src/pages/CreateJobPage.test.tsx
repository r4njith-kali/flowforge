import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { Job } from '../types/job'
import { CreateJobPage } from './CreateJobPage'

const { createJob } = vi.hoisted(() => ({ createJob: vi.fn() }))

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, createJob }
})

const createdJob: Job = {
  id: '8f32f376-b61b-4b8f-9b45-c2cb1ab2b911',
  jobType: 'demo.echo',
  payload: { message: 'Hello from FlowForge' },
  status: 'PENDING',
  createdAt: '2026-10-13T12:00:00Z',
  updatedAt: '2026-10-13T12:00:00Z',
  scheduledAt: null,
  startedAt: null,
  completedAt: null,
  cancelRequestedAt: null,
  attemptCount: 0,
  maxAttempts: 5,
  retryDelaySeconds: 0,
  version: 0,
}

function renderForm() {
  return render(
    <MemoryRouter initialEntries={['/jobs/new']}>
      <Routes>
        <Route element={<CreateJobPage />} path="/jobs/new" />
        <Route
          element={<h1>Opened job details</h1>}
          path="/jobs/:id"
        />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CreateJobPage', () => {
  it('submits a typed payload and opens the created job', async () => {
    createJob.mockResolvedValue(createdJob)
    renderForm()

    fireEvent.click(screen.getByRole('button', { name: 'Submit job' }))

    await screen.findByRole('heading', { name: 'Opened job details' })
    expect(createJob).toHaveBeenCalledWith(
      {
        jobType: 'demo.echo',
        payload: { message: 'Hello from FlowForge' },
        maxAttempts: 5,
        retryDelaySeconds: 0,
      },
      undefined,
    )
  })

  it('blocks submission when the payload is not a JSON object', async () => {
    renderForm()
    fireEvent.change(screen.getByLabelText(/JSON payload/), {
      target: { value: '[]' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Submit job' }))

    expect(await screen.findByText('Payload must be a JSON object.')).toBeInTheDocument()
    await waitFor(() => expect(createJob).not.toHaveBeenCalled())
  })
})
