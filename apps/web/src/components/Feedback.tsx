import type { ReactNode } from 'react'
import { Icon } from './Icon'

export function ErrorNotice({
  title = 'Something went wrong',
  children,
  onRetry,
}: {
  title?: string
  children: ReactNode
  onRetry?: () => void
}) {
  return (
    <div className="notice notice-error" role="alert">
      <Icon name="warning" />
      <div>
        <strong>{title}</strong>
        <p>{children}</p>
        {onRetry && (
          <button className="text-button" onClick={onRetry} type="button">
            Try again
          </button>
        )}
      </div>
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <span className="empty-mark">
        <Icon name="activity" />
      </span>
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </div>
  )
}
