import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  it('labels each state using the lifecycle name', () => {
    render(<StatusBadge status="DISPATCHED" />)

    expect(screen.getByText('Dispatched')).toBeInTheDocument()
    expect(
      screen.getByText('Dispatched').closest('.status-badge'),
    ).toHaveClass('status-dispatched')
  })
})
