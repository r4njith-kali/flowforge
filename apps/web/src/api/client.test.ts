import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  cancelJob,
  createJob,
  getJobAttempts,
  listJobs,
  registerAuthentication,
} from './client'
import type { Job } from '../types/job'

const job: Job = {
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

afterEach(() => vi.unstubAllGlobals())

describe('job API client', () => {
  it('encodes server-side list filters and pagination', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], page: 1, size: 10 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await listJobs({
      page: 1,
      size: 10,
      status: 'RUNNING',
      jobType: 'demo.echo',
      sortBy: 'createdAt',
      direction: 'DESC',
    })

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/jobs?page=1&size=10&status=RUNNING&jobType=demo.echo&sortBy=createdAt&direction=DESC',
    )
  })

  it('sends create payload and optional idempotency key', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(job), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await createJob(
      { jobType: 'demo.echo', payload: { message: 'hello' } },
      'request-1',
    )

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe('request-1')
    expect(JSON.parse(String(init.body))).toEqual({
      jobType: 'demo.echo',
      payload: { message: 'hello' },
    })
  })

  it('loads attempts for a single encoded job ID', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(getJobAttempts('job/id')).resolves.toEqual([])
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/jobs/job%2Fid/attempts')
  })

  it('distinguishes an accepted running cancellation from completed cancellation', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(job), {
        status: 202,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(cancelJob(job.id)).resolves.toEqual({ job, accepted: true })
    expect(fetchMock.mock.calls[0][0]).toBe(`/api/v1/jobs/${job.id}/cancel`)
  })

  it('returns a non-accepted result for HTTP 200 cancellation', async () => {
    const cancelledJob = { ...job, status: 'CANCELLED' as const }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(cancelledJob), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    await expect(cancelJob(job.id)).resolves.toEqual({
      job: cancelledJob,
      accepted: false,
    })
  })

  it('surfaces API conflict codes and detail text', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            title: 'Conflict',
            status: 409,
            detail: 'Job cannot be cancelled in its current state',
            code: 'JOB_STATE_CONFLICT',
          }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    await expect(cancelJob(job.id)).rejects.toMatchObject({
      status: 409,
      code: 'JOB_STATE_CONFLICT',
      message: 'Job cannot be cancelled in its current state',
    })
  })

  it('attaches bearer tokens without sending browser cookies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: [], page: 0, size: 20 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const unregister = registerAuthentication(() => 'access-token', () => undefined)

    await listJobs()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-token')
    expect(init.credentials).toBe('omit')
    unregister()
  })

  it('translates forbidden responses into a clear permission message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ title: 'Forbidden', code: 'FORBIDDEN' }),
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    await expect(createJob({ jobType: 'demo.echo', payload: {} }))
      .rejects.toMatchObject({
        status: 403,
        message: 'Your account does not have permission to perform this action.',
      })
  })

  it('notifies the auth provider when the API rejects an expired token', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response('{}', { status: 401 }),
    ))
    const onUnauthorized = vi.fn()
    const unregister = registerAuthentication(() => 'expired-token', onUnauthorized)

    await expect(listJobs()).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledOnce()
    unregister()
  })
})
