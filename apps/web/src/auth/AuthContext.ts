import { createContext, useContext } from 'react'
import type { User } from 'oidc-client-ts'

export type FlowForgeRole = 'ADMIN' | 'OPERATOR' | 'VIEWER'
export type AuthStatus = 'loading' | 'authenticated' | 'anonymous' | 'error'

export interface AuthContextValue {
  status: AuthStatus
  user: User | null
  error: string | null
  roles: FlowForgeRole[]
  canSubmit: boolean
  canCancel: boolean
  login: () => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within an AuthProvider')
  return context
}
