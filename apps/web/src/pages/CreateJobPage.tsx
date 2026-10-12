import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, createJob } from '../api/client'
import { ErrorNotice } from '../components/Feedback'
import { Icon } from '../components/Icon'

const defaultPayload = '{\n  "message": "Hello from FlowForge"\n}'

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function CreateJobPage() {
  const navigate = useNavigate()
  const [jobType, setJobType] = useState('demo.echo')
  const [payload, setPayload] = useState(defaultPayload)
  const [scheduledAt, setScheduledAt] = useState('')
  const [maxAttempts, setMaxAttempts] = useState(5)
  const [retryDelaySeconds, setRetryDelaySeconds] = useState(0)
  const [idempotencyKey, setIdempotencyKey] = useState('')
  const [payloadError, setPayloadError] = useState('')
  const [submitError, setSubmitError] = useState<Error | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setSubmitError(null)
    setPayloadError('')
    let parsedPayload: unknown
    try {
      parsedPayload = JSON.parse(payload)
      if (!isJsonObject(parsedPayload)) {
        throw new Error('Payload must be a JSON object.')
      }
    } catch (error) {
      setPayloadError(
        error instanceof Error ? error.message : 'Enter a valid JSON object.',
      )
      return
    }

    setIsSubmitting(true)
    try {
      const job = await createJob(
        {
          jobType: jobType.trim(),
          payload: parsedPayload,
          ...(scheduledAt ? { scheduledAt: new Date(scheduledAt).toISOString() } : {}),
          maxAttempts,
          retryDelaySeconds,
        },
        idempotencyKey.trim() || undefined,
      )
      navigate(`/jobs/${job.id}`, {
        state: { notice: 'Job accepted by the control plane.' },
      })
    } catch (error) {
      setSubmitError(
        error instanceof Error ? error : new Error('Job submission failed.'),
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  const problem = submitError instanceof ApiError ? submitError.problem : undefined

  return (
    <div className="page-stack narrow-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / JOBS / NEW</p>
          <h1>Submit a job</h1>
          <p className="page-subtitle">
            Create a durable job and let the worker fleet handle execution.
          </p>
        </div>
        <Link className="button button-ghost" to="/jobs">
          <Icon name="arrowLeft" />
          Back to jobs
        </Link>
      </div>

      {submitError && (
        <ErrorNotice title="Job could not be submitted">
          {submitError.message}
          {problem?.errors?.length ? (
            <ul className="validation-list">
              {problem.errors.map((item) => (
                <li key={`${item.field}-${item.message}`}>
                  <strong>{item.field}:</strong> {item.message}
                </li>
              ))}
            </ul>
          ) : null}
        </ErrorNotice>
      )}

      <form className="form-panel" onSubmit={submit}>
        <div className="form-section-heading">
          <span className="section-number">01</span>
          <div>
            <h2>Job details</h2>
            <p>Choose the registered handler that should process this job.</p>
          </div>
        </div>
        <label className="form-field">
          <span>Job type <span className="required-mark">*</span></span>
          <input
            maxLength={200}
            onChange={(event) => setJobType(event.target.value)}
            placeholder="e.g. demo.echo"
            required
            value={jobType}
          />
          <small>Must match a handler registered by the worker service.</small>
        </label>
        <label className="form-field">
          <span>JSON payload <span className="required-mark">*</span></span>
          <textarea
            aria-describedby={payloadError ? 'payload-error' : 'payload-help'}
            aria-invalid={Boolean(payloadError)}
            className="code-input"
            onChange={(event) => setPayload(event.target.value)}
            rows={8}
            value={payload}
          />
          {payloadError ? (
            <small className="field-error" id="payload-error">
              {payloadError}
            </small>
          ) : (
            <small id="payload-help">
              Provide a JSON object that matches the selected handler.
            </small>
          )}
        </label>
        <div className="form-section-heading form-section-spaced">
          <span className="section-number">02</span>
          <div>
            <h2>Execution policy</h2>
            <p>Control scheduling and bounded retries.</p>
          </div>
        </div>
        <label className="form-field">
          <span>Schedule for</span>
          <input
            onChange={(event) => setScheduledAt(event.target.value)}
            type="datetime-local"
            value={scheduledAt}
          />
          <small>Leave empty to make the job eligible immediately.</small>
        </label>
        <div className="form-grid">
          <label className="form-field">
            <span>Maximum attempts</span>
            <input
              max={100}
              min={1}
              onChange={(event) => setMaxAttempts(Number(event.target.value))}
              type="number"
              value={maxAttempts}
            />
            <small>Allowed range: 1–100.</small>
          </label>
          <label className="form-field">
            <span>Retry delay (seconds)</span>
            <input
              max={86400}
              min={0}
              onChange={(event) => setRetryDelaySeconds(Number(event.target.value))}
              type="number"
              value={retryDelaySeconds}
            />
            <small>Base delay before retry; 0 seconds by default.</small>
          </label>
        </div>
        <details className="optional-field">
          <summary>Advanced · idempotency key</summary>
          <label className="form-field">
            <span>Idempotency-Key</span>
            <input
              maxLength={200}
              onChange={(event) => setIdempotencyKey(event.target.value)}
              placeholder="Optional key for safe request retries"
              value={idempotencyKey}
            />
            <small>
              Reusing this key with an equivalent request returns the original
              job. A different request returns a conflict.
            </small>
          </label>
        </details>
        <div className="form-actions">
          <Link className="button button-ghost" to="/jobs">
            Cancel
          </Link>
          <button
            className="button button-primary"
            disabled={isSubmitting}
            type="submit"
          >
            {isSubmitting ? <span className="spinner spinner-small" /> : <Icon name="plus" />}
            {isSubmitting ? 'Submitting…' : 'Submit job'}
          </button>
        </div>
      </form>
    </div>
  )
}
