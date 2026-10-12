import { useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { oidcManager } from './oidc'
import { useAuth, type FlowForgeRole } from './AuthContext'

let redirectCallback:
  | {
      search: string
      result: ReturnType<typeof oidcManager.signinRedirectCallback>
    }
  | undefined

function getRedirectCallback(search: string) {
  if (!redirectCallback || redirectCallback.search !== search) {
    redirectCallback = {
      search,
      result: oidcManager.signinRedirectCallback(),
    }
  }
  return redirectCallback.result
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, error, login } = useAuth()

  if (status === 'loading') {
    return (
      <div aria-label="Restoring sign-in session" className="page-loading">
        <span className="spinner" />
        Restoring your sign-in session…
      </div>
    )
  }
  if (status === 'error' || status === 'anonymous') {
    return (
      <main className="auth-screen">
        <div className="auth-card">
          <span className="brand-name">flowforge</span>
          <h1>Sign in to continue</h1>
          <p>Your identity provider will authenticate you securely.</p>
          {error && <p className="auth-error" role="alert">{error}</p>}
          <button className="button button-primary" onClick={() => void login()} type="button">
            Sign in with FlowForge
          </button>
        </div>
      </main>
    )
  }
  return children
}

export function RequireRole({
  roles,
  children,
}: {
  roles: FlowForgeRole[]
  children: ReactNode
}) {
  const auth = useAuth()
  if (auth.roles.some((role) => roles.includes(role))) return children
  return (
    <main className="auth-screen">
      <div className="auth-card">
        <h1>Access denied</h1>
        <p>Your account does not have permission to use this feature.</p>
        <Link className="button button-secondary" to="/">Return to overview</Link>
      </div>
    </main>
  )
}

export function AuthCallbackPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    void getRedirectCallback(location.search)
      .then((user) => {
        if (!active) return
        const returnTo =
          typeof user.state === 'object' &&
          user.state !== null &&
          'returnTo' in user.state &&
          typeof user.state.returnTo === 'string' &&
          user.state.returnTo.startsWith('/') &&
          !user.state.returnTo.startsWith('//')
            ? user.state.returnTo
            : '/'
        navigate(returnTo, { replace: true })
      })
      .catch((callbackError: unknown) => {
        if (active) {
          setError(
            callbackError instanceof Error
              ? callbackError.message
              : 'The identity provider sign-in could not be completed.',
          )
        }
      })
    return () => {
      active = false
    }
  }, [location.key, location.search, navigate])

  return (
    <main className="auth-screen">
      <div className="auth-card">
        <h1>{error ? 'Sign-in failed' : 'Completing sign-in'}</h1>
        {error ? (
          <>
            <p className="auth-error" role="alert">{error}</p>
            <Link className="button button-secondary" to="/">Return to FlowForge</Link>
          </>
        ) : (
          <p>Verifying your identity…</p>
        )}
      </div>
    </main>
  )
}
