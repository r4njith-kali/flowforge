import type {
  ApiProblem,
  CreateJobRequest,
  Job,
  JobFilters,
  JobPage,
} from '../types/job'

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly problem?: ApiProblem

  constructor(
    message: string,
    status: number,
    code?: string,
    problem?: ApiProblem,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.problem = problem
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ApiProblem
    throw new ApiError(
      problem.detail ?? problem.title ?? `Request failed (${response.status})`,
      response.status,
      problem.code,
      problem,
    )
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function listJobs(filters: JobFilters = {}): Promise<JobPage> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') query.set(key, String(value))
  }
  return request(`/api/v1/jobs?${query.toString()}`)
}

export function getJob(id: string): Promise<Job> {
  return request(`/api/v1/jobs/${encodeURIComponent(id)}`)
}

export function createJob(
  body: CreateJobRequest,
  idempotencyKey?: string,
): Promise<Job> {
  return request('/api/v1/jobs', {
    method: 'POST',
    body: JSON.stringify(body),
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}

export type CancellationResult = {
  job: Job
  accepted: boolean
}

export async function cancelJob(id: string): Promise<CancellationResult> {
  const response = await fetch(
    `/api/v1/jobs/${encodeURIComponent(id)}/cancel`,
    {
      method: 'POST',
      headers: { Accept: 'application/json' },
    },
  )

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ApiProblem
    throw new ApiError(
      problem.detail ?? problem.title ?? `Request failed (${response.status})`,
      response.status,
      problem.code,
      problem,
    )
  }

  if (response.status !== 200 && response.status !== 202) {
    throw new ApiError(
      `Unexpected cancellation response (${response.status})`,
      response.status,
    )
  }

  return {
    job: (await response.json()) as Job,
    accepted: response.status === 202,
  }
}

export interface HealthResponse {
  status: string
}

export function getControlPlaneHealth(): Promise<HealthResponse> {
  return request('/actuator/health')
}
