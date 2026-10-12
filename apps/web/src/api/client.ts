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

type AccessTokenProvider = () => string | null
type UnauthorizedHandler = () => void | Promise<void>

let accessTokenProvider: AccessTokenProvider = () => null
let unauthorizedHandler: UnauthorizedHandler = () => undefined

export function registerAuthentication(
  getAccessToken: AccessTokenProvider,
  onUnauthorized: UnauthorizedHandler,
): () => void {
  accessTokenProvider = getAccessToken
  unauthorizedHandler = onUnauthorized
  return () => {
    if (accessTokenProvider === getAccessToken) {
      accessTokenProvider = () => null
      unauthorizedHandler = () => undefined
    }
  }
}

async function authenticatedFetch(path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers)
  headers.set('Accept', headers.get('Accept') ?? 'application/json')
  if (init?.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const accessToken = accessTokenProvider()
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)

  const response = await fetch(path, {
    ...init,
    credentials: 'omit',
    headers,
  })
  if (response.status === 401 && accessToken) await unauthorizedHandler()
  return response
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await authenticatedFetch(path, init)

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ApiProblem
    const message = response.status === 401
      ? 'Your sign-in session is no longer valid. Sign in again to continue.'
      : response.status === 403
        ? 'Your account does not have permission to perform this action.'
        : problem.detail ?? problem.title ?? `Request failed (${response.status})`
    throw new ApiError(
      message,
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
  const response = await authenticatedFetch(
    `/api/v1/jobs/${encodeURIComponent(id)}/cancel`,
    {
      method: 'POST',
    },
  )

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ApiProblem
    const message = response.status === 401
      ? 'Your sign-in session is no longer valid. Sign in again to continue.'
      : response.status === 403
        ? 'Your account does not have permission to cancel this job.'
        : problem.detail ?? problem.title ?? `Request failed (${response.status})`
    throw new ApiError(
      message,
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
