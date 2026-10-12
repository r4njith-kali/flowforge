import { createContext, useContext } from 'react'

export const REFRESH_INTERVALS = [5_000, 15_000, 30_000] as const

export const RefreshIntervalContext = createContext<number>(5_000)

export function useRefreshInterval(): number {
  return useContext(RefreshIntervalContext)
}

export function configuredRefreshInterval(): number {
  const configured = Number(import.meta.env.VITE_REFRESH_INTERVAL_MS)
  return REFRESH_INTERVALS.find((interval) => interval === configured) ?? 5_000
}
