import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import { User } from 'oidc-client-ts'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { signinRedirectCallback } = vi.hoisted(() => ({
  signinRedirectCallback: vi.fn(),
}))

vi.mock('./oidc', () => ({
  oidcManager: { signinRedirectCallback },
}))

import { AuthCallbackPage } from './AuthRoutes'

describe('AuthCallbackPage', () => {
  beforeEach(() => {
    signinRedirectCallback.mockReset()
  })

  it('exchanges an authorization code once when React StrictMode replays effects', async () => {
    const now = Math.floor(Date.now() / 1000)
    signinRedirectCallback.mockResolvedValue(
      new User({
        access_token: 'test-access-token',
        token_type: 'Bearer',
        profile: {
          iss: 'https://identity.flowforge.test/realms/flowforge',
          aud: 'flowforge-web',
          exp: now + 300,
          iat: now,
          sub: 'user-1',
        },
      }),
    )

    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/auth/callback?code=single-use-code']}>
          <Routes>
            <Route element={<AuthCallbackPage />} path="/auth/callback" />
            <Route element={<h1>Dashboard</h1>} path="/" />
          </Routes>
        </MemoryRouter>
      </StrictMode>,
    )

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(signinRedirectCallback).toHaveBeenCalledOnce()
  })
})
