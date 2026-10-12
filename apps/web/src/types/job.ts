export const JOB_STATUSES = [
  'PENDING',
  'SCHEDULED',
  'DISPATCHING',
  'DISPATCHED',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const

export type JobStatus = (typeof JOB_STATUSES)[number]

export interface Job {
  id: string
  jobType: string
  payload: Record<string, unknown>
  status: JobStatus
  createdAt: string
  updatedAt: string
  scheduledAt: string | null
  startedAt: string | null
  completedAt: string | null
  cancelRequestedAt: string | null
  attemptCount: number
  maxAttempts: number
  retryDelaySeconds: number
  version: number
}

export interface JobPage {
  items: Job[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface CreateJobRequest {
  jobType: string
  payload: Record<string, unknown>
  scheduledAt?: string
  maxAttempts?: number
  retryDelaySeconds?: number
}

export interface JobFilters {
  page?: number
  size?: number
  status?: JobStatus
  jobType?: string
  sortBy?: 'createdAt' | 'updatedAt' | 'scheduledAt' | 'jobType' | 'status'
  direction?: 'ASC' | 'DESC'
}

export interface ApiProblem {
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  errors?: { field: string; message: string }[]
}
