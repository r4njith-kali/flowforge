import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import type { User, UserManager } from 'oidc-client-ts'
import { registerAuthentication } from '../api/client'
import { AuthContext, type AuthContextValue, type AuthStatus, type FlowForgeRole } from './AuthContext'
import { oidcManager } from './oidc'

function isFlowForgeRole(role: unknown): role is FlowForgeRole {
  return role === 'ADMIN' || role === 'OPERATOR' || role === 'VIEWER'
}

function messageFrom(error: unknown): string {
  return error instanceof Error ? error.message : 'Authentication failed.'
}

function rolesFromUser(user: User | null): FlowForgeRole[] {
  if (!user) return []
  const realmAccess = user.profile.realm_access
  if (typeof realmAccess !== 'object' || realmAccess === null) return []
  const roles = 'roles' in realmAccess ? realmAccess.roles : undefined
  if (!Array.isArray(roles)) return []
  return roles.filter(isFlowForgeRole)
}

export function AuthProvider({
  children,
  manager = oidcManager,
}: {
  children: ReactNode
  manager?: UserManager
}) {
  const [user, setUser] = useState<User | null>(null)
  const [status, setStatus] = useState<AuthStatus>('loading')
  const [error, setError] = useState<string | null>(null)
  const userRef = useRef<User | null>(null)

  useEffect(() => {
    let active = true
    const setAuthenticatedUser = (loadedUser: User) => {
      if (!active) return
      userRef.current = loadedUser
      setUser(loadedUser)
      setError(null)
      setStatus('authenticated')
    }
    const setAnonymous = () => {
      if (!active) return
      userRef.current = null
      setUser(null)
      setStatus('anonymous')
    }
    const expireSession = () => {
      setAnonymous()
      void manager.removeUser().catch((removeError: unknown) => {
        if (active) setError(messageFrom(removeError))
      })
    }
    const handleRenewError = (renewError: Error) => {
      if (!active) return
      setAnonymous()
      setError(`Your session could not be renewed: ${renewError.message}`)
      void manager.removeUser().catch((removeError: unknown) => {
        if (active) setError(messageFrom(removeError))
      })
    }

    manager.events.addUserLoaded(setAuthenticatedUser)
    manager.events.addUserUnloaded(setAnonymous)
    manager.events.addAccessTokenExpired(expireSession)
    manager.events.addSilentRenewError(handleRenewError)

    const restoreSession = async () => {
      try {
        const restored = await manager.getUser()
        if (!active) return
        if (restored && !restored.expired) {
          setAuthenticatedUser(restored)
          return
        }
        if (restored?.refresh_token) {
          const renewed = await manager.signinSilent()
          if (active && renewed && !renewed.expired) {
            setAuthenticatedUser(renewed)
            return
          }
        }
        if (restored) await manager.removeUser()
        setAnonymous()
      } catch (restoreError) {
        if (!active) return
        userRef.current = null
        setUser(null)
        setStatus('anonymous')
        setError(`Your sign-in session could not be restored: ${messageFrom(restoreError)}`)
      }
    }
    void restoreSession()

    const unregisterAuthentication = registerAuthentication(
      () => userRef.current?.access_token ?? null,
      async () => {
        setAnonymous()
        await manager.removeUser()
      },
    )

    return () => {
      active = false
      unregisterAuthentication()
      manager.events.removeUserLoaded(setAuthenticatedUser)
      manager.events.removeUserUnloaded(setAnonymous)
      manager.events.removeAccessTokenExpired(expireSession)
      manager.events.removeSilentRenewError(handleRenewError)
    }
  }, [manager])

  const login = useCallback(async () => {
    setError(null)
    try {
      await manager.signinRedirect({
        state: { returnTo: `${window.location.pathname}${window.location.search}` },
      })
    } catch (loginError) {
      setError(`Sign-in could not be started: ${messageFrom(loginError)}`)
    }
  }, [manager])

  const logout = useCallback(async () => {
    setError(null)
    try {
      await manager.signoutRedirect()
    } catch (logoutError) {
      setError(`Sign-out could not be completed: ${messageFrom(logoutError)}`)
    }
  }, [manager])

  const roles = useMemo(() => rolesFromUser(user), [user])
  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      error,
      roles,
      canSubmit: roles.includes('ADMIN') || roles.includes('OPERATOR'),
      canCancel: roles.includes('ADMIN') || roles.includes('OPERATOR'),
      login,
      logout,
    }),
    [status, user, error, roles, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
