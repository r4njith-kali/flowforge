import { act, fireEvent, render, screen } from '@testing-library/react'
import { User, UserManager, type AccessTokenCallback } from 'oidc-client-ts'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from './AuthContext'
import { AuthProvider } from './AuthProvider'

function user(roles: string[], expiresAt = Math.floor(Date.now() / 1000) + 3600) {
  return new User({
    access_token: 'test-access-token',
    token_type: 'Bearer',
    refresh_token: 'test-refresh-token',
    expires_at: expiresAt,
    profile: {
      iss: 'https://identity.flowforge.test/realms/flowforge',
      aud: 'flowforge-web',
      exp: expiresAt,
      iat: Math.floor(Date.now() / 1000),
      sub: 'operator-1',
      realm_access: { roles },
    },
  })
}

function manager() {
  return new UserManager({
    authority: 'https://identity.flowforge.test/realms/flowforge',
    client_id: 'flowforge-web',
    redirect_uri: 'http://localhost/auth/callback',
    response_type: 'code',
    automaticSilentRenew: false,
    monitorSession: false,
  })
}

function AuthState() {
  const auth = useAuth()
  return (
    <div>
      <span>{auth.status}</span>
      <span>{auth.roles.join(',')}</span>
      <span>{String(auth.canSubmit)}</span>
      <button onClick={() => void auth.login()} type="button">Sign in</button>
      <button onClick={() => void auth.logout()} type="button">Sign out</button>
    </div>
  )
}

afterEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('AuthProvider', () => {
  it('restores the session and derives only FlowForge roles', async () => {
    const oidc = manager()
    vi.spyOn(oidc, 'getUser').mockResolvedValue(user([
      'OPERATOR',
      'offline_access',
    ]))

    render(
      <AuthProvider manager={oidc}>
        <AuthState />
      </AuthProvider>,
    )

    expect(await screen.findByText('authenticated')).toBeInTheDocument()
    expect(screen.getByText('OPERATOR')).toBeInTheDocument()
    expect(screen.getByText('true')).toBeInTheDocument()
  })

  it('starts the standards-based redirect flow for an anonymous user', async () => {
    const oidc = manager()
    vi.spyOn(oidc, 'getUser').mockResolvedValue(null)
    const redirect = vi.spyOn(oidc, 'signinRedirect').mockResolvedValue()

    render(
      <AuthProvider manager={oidc}>
        <AuthState />
      </AuthProvider>,
    )
    await screen.findByText('anonymous')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(redirect).toHaveBeenCalledWith({
      state: { returnTo: '/' },
    })
  })

  it('delegates logout to the identity provider', async () => {
    const oidc = manager()
    vi.spyOn(oidc, 'getUser').mockResolvedValue(user(['VIEWER']))
    const redirect = vi.spyOn(oidc, 'signoutRedirect').mockResolvedValue()

    render(
      <AuthProvider manager={oidc}>
        <AuthState />
      </AuthProvider>,
    )
    await screen.findByText('VIEWER')
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(redirect).toHaveBeenCalledOnce()
  })

  it('clears the session when the OIDC client reports token expiry', async () => {
    const oidc = manager()
    vi.spyOn(oidc, 'getUser').mockResolvedValue(user(['OPERATOR']))
    const removeUser = vi.spyOn(oidc, 'removeUser').mockResolvedValue()
    let expired: AccessTokenCallback | undefined
    vi.spyOn(oidc.events, 'addAccessTokenExpired').mockImplementation((callback) => {
      expired = callback
      return () => undefined
    })

    render(
      <AuthProvider manager={oidc}>
        <AuthState />
      </AuthProvider>,
    )
    await screen.findByText('authenticated')
    await act(async () => {
      await expired?.()
    })

    expect(await screen.findByText('anonymous')).toBeInTheDocument()
    expect(removeUser).toHaveBeenCalledOnce()
  })
})
