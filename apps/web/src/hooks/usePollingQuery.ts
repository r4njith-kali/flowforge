import { useCallback, useEffect, useState } from 'react'

export function usePollingQuery<T>(
  loader: () => Promise<T>,
  intervalMs: number,
): {
  data: T | null
  error: Error | null
  isLoading: boolean
  refresh: () => void
  setData: (value: T) => void
} {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<Error | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [refreshToken, setRefreshToken] = useState(0)
  const refresh = useCallback(() => setRefreshToken((current) => current + 1), [])

  useEffect(() => {
    let active = true
    let inFlight = false

    const load = async () => {
      if (inFlight) return
      inFlight = true
      try {
        const nextData = await loader()
        if (active) {
          setData(nextData)
          setError(null)
        }
      } catch (caught) {
        if (active) {
          setError(
            caught instanceof Error ? caught : new Error('Unexpected API error'),
          )
        }
      } finally {
        inFlight = false
        if (active) setIsLoading(false)
      }
    }

    void load()
    const timer = window.setInterval(() => void load(), intervalMs)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [intervalMs, loader, refreshToken])

  return { data, error, isLoading, refresh, setData }
}
